package com.example.springai.infra.concurrent;

import com.example.springai.core.context.AgentContext;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 总截止时间覆盖排队、改写、向量化、工具及模型阶段。取消会中断 worker，而不是只改标记。
 * 外部适配器仍须设置自己的 I/O 超时；不合作的第三方调用不能靠 Java 中断保证停止。
 * 定时器只改变状态/中断任务，终态通知另走有界池，禁止在定时器线程发送网络事件。
 */
@Slf4j
public final class TurnTaskSupervisor implements AutoCloseable {
    private final Executor worker;
    private final Semaphore admission = new Semaphore(512);
    private final Map<String, Run> active = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,r -> daemon(r,"agent-deadlines"));
    private final ThreadPoolExecutor notices = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(512),r -> daemon(r,"agent-terminal-events"),new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong rejected=new AtomicLong(),timedOut=new AtomicLong(),cancelled=new AtomicLong();
    private final AtomicBoolean closed=new AtomicBoolean();
    public TurnTaskSupervisor(Executor worker) {
        this.worker=Objects.requireNonNull(worker); deadlines.setRemoveOnCancelPolicy(true);
    }
    public void submit(AgentContext context,Runnable task) {
        if (closed.get() || !admission.tryAcquire()) { rejected.incrementAndGet(); throw new RejectedExecutionException("QUEUE_SATURATED"); }
        Run run=new Run(context,task);
        Run previous=active.put(context.getSessionId(),run);
        if(previous!=null) previous.cancel("TURN_SUPERSEDED",false);
        try {
            long delay=context.getDeadlineMillis()>0 ? Math.max(0,context.getDeadlineMillis()-System.currentTimeMillis()) : 60000;
            run.timer=deadlines.schedule(() -> run.cancel("TURN_TIMEOUT",true),delay,TimeUnit.MILLISECONDS);
            if(run.finished.get()) run.timer.cancel(false);
            worker.execute(run.future);
        } catch(RejectedExecutionException ex) {
            rejected.incrementAndGet(); run.cancel("QUEUE_SATURATED",false); throw ex;
        }
    }
    public boolean cancel(String sessionId) {
        Run run=active.get(sessionId); return run!=null && run.cancel("TURN_CANCELLED",false);
    }
    public int activeCount() { return active.size(); }
    public long rejectedCount() { return rejected.get(); }
    public long timeoutCount() { return timedOut.get(); }
    public long cancelledCount() { return cancelled.get(); }
    private final class Run {
        final AgentContext context;
        final AtomicBoolean finished=new AtomicBoolean();
        final FutureTask<Void> future;
        volatile ScheduledFuture<?> timer;
        Run(AgentContext context,Runnable task) {
            this.context=context;
            future=new FutureTask<>(() -> { task.run(); return null; }) {
                @Override protected void done() { finish(); }
            };
        }
        boolean cancel(String reason,boolean timeout) {
            if(!finished.compareAndSet(false,true)) return false;
            context.cancel(); future.cancel(true);
            active.remove(context.getSessionId(),this); admission.release();
            ScheduledFuture<?> scheduled=timer; if(scheduled!=null) scheduled.cancel(false);
            if(timeout) timedOut.incrementAndGet(); else cancelled.incrementAndGet();
            try { notices.execute(() -> {
                try { context.getSink().error(reason + ": 本回合已停止，请核查结果后再操作"); }
                catch(RuntimeException ex) { log.debug("终态通知未送达 turnId={}",context.getTurnId(),ex); }
            }); } catch(RejectedExecutionException ex) { log.warn("终态通知队列已满 turnId={}",context.getTurnId()); }
            return true;
        }
        void finish() {
            if(!finished.compareAndSet(false,true)) return;
            active.remove(context.getSessionId(),this); admission.release();
            ScheduledFuture<?> scheduled=timer; if(scheduled!=null) scheduled.cancel(false);
        }
    }
    private static Thread daemon(Runnable task,String name) { Thread t=new Thread(task,name); t.setDaemon(true); return t; }
    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        new ArrayList<>(active.values()).forEach(run -> run.cancel("SHUTDOWN",false));
        deadlines.shutdownNow(); notices.shutdown();
    }
}
