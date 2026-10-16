package com.example.springai.infra.concurrent;

import com.example.springai.core.context.AgentContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns admission and turn activation as one transaction. A rejected replacement never
 * cancels the old task or changes its event binding. No business work or network I/O
 * runs under the lifecycle lock. Executors must accept promptly or reject explicitly.
 * Interruption remains cooperative; adapters still need their own I/O deadlines.
 */
@Slf4j
public final class TurnTaskSupervisor implements AutoCloseable {
    private static final Runnable NO_OP = () -> { };
    private final Object lifecycleLock = new Object();
    private final Executor worker;
    private final Semaphore admission;
    private final Map<String, Run> active = new HashMap<>();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(
            1, task -> daemon(task, "agent-deadlines"));
    private final ThreadPoolExecutor notices = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(512),
            task -> daemon(task, "agent-terminal-events"), new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();
    private boolean closed;

    public TurnTaskSupervisor(Executor worker) {
        this(worker, 512);
    }

    TurnTaskSupervisor(Executor worker, int capacity) {
        this.worker = Objects.requireNonNull(worker);
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        admission = new Semaphore(capacity);
        deadlines.setRemoveOnCancelPolicy(true);
    }

    public void submit(AgentContext context, Runnable task) {
        submit(context, task, NO_OP, NO_OP);
    }

    /** Activation/release callbacks must be non-blocking, memory-only operations. */
    public void submit(AgentContext context, Runnable task, Runnable activate, Runnable release) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(task);
        Objects.requireNonNull(activate);
        Objects.requireNonNull(release);
        Objects.requireNonNull(context.getSessionId());
        Run run = new Run(context, task, activate, release);
        synchronized (lifecycleLock) {
            Run previous = active.get(context.getSessionId());
            if (previous != null && Objects.equals(previous.context.getTurnId(), context.getTurnId())) {
                throw new IllegalArgumentException("turnId is already active");
            }
            if (closed || !admission.tryAcquire()) {
                rejected.incrementAndGet();
                throw new RejectedExecutionException("QUEUE_SATURATED");
            }
            try {
                long delay = context.getDeadlineMillis() > 0
                        ? Math.max(0, context.getDeadlineMillis() - System.currentTimeMillis()) : 60000;
                run.timer = deadlines.schedule(() -> {
                    synchronized (lifecycleLock) {
                        run.cancelLocked("TURN_TIMEOUT", true);
                    }
                }, delay, TimeUnit.MILLISECONDS);
                // The worker can start immediately, but cannot execute user code until commit.
                // Inline executors are deferred until after this lock has been released.
                worker.execute(run::dispatch);
            } catch (RuntimeException ex) {
                run.rejectLocked();
                throw ex;
            }
            try {
                activate.run();
            } catch (RuntimeException ex) {
                run.releaseBinding();
                if (previous != null) previous.activate.run();
                run.rejectLocked();
                throw ex;
            }
            active.put(context.getSessionId(), run);
            if (previous != null) previous.cancelLocked("TURN_SUPERSEDED", false);
            run.accepted.complete(true);
        }
        if (run.inline) run.executeAccepted();
    }

    public boolean cancel(String sessionId) {
        synchronized (lifecycleLock) {
            Run run = active.get(sessionId);
            return run != null && run.cancelLocked("TURN_CANCELLED", false);
        }
    }

    /** A delayed browser cancellation must not stop a newer turn in the same session. */
    public boolean cancel(String sessionId, String turnId) {
        if (turnId == null || turnId.isBlank()) return false;
        synchronized (lifecycleLock) {
            Run run = active.get(sessionId);
            return run != null && turnId.equals(run.context.getTurnId())
                    && run.cancelLocked("TURN_CANCELLED", false);
        }
    }

    public int activeCount() {
        synchronized (lifecycleLock) { return active.size(); }
    }
    public long rejectedCount() { return rejected.get(); }
    public long timeoutCount() { return timedOut.get(); }
    public long cancelledCount() { return cancelled.get(); }

    private final class Run {
        private final AgentContext context;
        private final Runnable activate;
        private final Runnable release;
        private final Thread submitter = Thread.currentThread();
        private final CompletableFuture<Boolean> accepted = new CompletableFuture<>();
        private final FutureTask<Void> future;
        private ScheduledFuture<?> timer;
        private boolean finished;
        private boolean inline;

        private Run(AgentContext context, Runnable task, Runnable activate, Runnable release) {
            this.context = context;
            this.activate = activate;
            this.release = release;
            future = new FutureTask<>(() -> { task.run(); return null; }) {
                @Override protected void done() { finish(); }
            };
        }

        private void dispatch() {
            if (Thread.currentThread() == submitter && !accepted.isDone()) {
                inline = true;
                return;
            }
            if (accepted.join()) executeAccepted();
        }

        private void executeAccepted() {
            synchronized (lifecycleLock) {
                if (finished) return;
                if (context.isExpired(System.currentTimeMillis())) {
                    cancelLocked("TURN_TIMEOUT", true);
                    return;
                }
            }
            future.run();
        }

        private void rejectLocked() {
            finished = true;
            context.cancel();
            future.cancel(false);
            if (timer != null) timer.cancel(false);
            admission.release();
            rejected.incrementAndGet();
            accepted.complete(false);
        }

        private boolean cancelLocked(String reason, boolean timeout) {
            if (finished) return false;
            finished = true;
            context.cancel();
            future.cancel(true);
            active.remove(context.getSessionId(), this);
            admission.release();
            if (timer != null) timer.cancel(false);
            if (timeout) timedOut.incrementAndGet(); else cancelled.incrementAndGet();
            try {
                notices.execute(() -> {
                    try {
                        context.getSink().error(reason + ": 本回合已停止，请核查结果后再操作");
                    } catch (RuntimeException ex) {
                        log.debug("终态通知未送达 turnId={}", context.getTurnId(), ex);
                    } finally {
                        releaseBinding();
                    }
                });
            } catch (RejectedExecutionException ex) {
                releaseBinding();
                log.warn("终态通知队列已满 turnId={}", context.getTurnId());
            }
            return true;
        }

        private void finish() {
            synchronized (lifecycleLock) {
                if (finished) return;
                finished = true;
                active.remove(context.getSessionId(), this);
                admission.release();
                if (timer != null) timer.cancel(false);
                releaseBinding();
            }
        }

        private void releaseBinding() {
            try { release.run(); }
            catch (RuntimeException ex) { log.warn("回合绑定清理失败 turnId={}", context.getTurnId(), ex); }
        }
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            new ArrayList<>(active.values()).forEach(run -> run.cancelLocked("SHUTDOWN", false));
            deadlines.shutdownNow();
            notices.shutdown();
        }
    }
}
