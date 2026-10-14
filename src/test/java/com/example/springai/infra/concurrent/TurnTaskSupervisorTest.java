package com.example.springai.infra.concurrent;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.event.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class TurnTaskSupervisorTest {
    private AgentContext context(String session,String turn,long timeout) {
        var ctx=AgentContext.builder().sessionId(session).turnId(turn).deadlineMillis(System.currentTimeMillis()+timeout).build();
        ctx.setSink(mock(AgentEventSink.class)); return ctx;
    }
    @Test void actualBlockingWorkerIsInterruptedAtDeadlineAndLateOutputIsRejected() throws Exception {
        var worker=Executors.newSingleThreadExecutor(); var started=new CountDownLatch(1); var interrupted=new CountDownLatch(1);
        try(var supervisor=new TurnTaskSupervisor(worker)) {
            var ctx=context("s","t",300); var sink=ctx.getSink(); var guarded=new CancellableAgentEventSink(ctx,sink);
            supervisor.submit(ctx,()-> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch(InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                assertFalse(guarded.message("late"));
            });
            assertTrue(started.await(1,TimeUnit.SECONDS)); assertTrue(interrupted.await(2,TimeUnit.SECONDS));
            assertTrue(ctx.isCancelled()); verify(sink,timeout(1000)).error(contains("TURN_TIMEOUT"));
            verify(sink,never()).message(anyString()); assertEquals(0,supervisor.activeCount()); assertEquals(1,supervisor.timeoutCount());
        } finally { worker.shutdownNow(); }
    }
    @Test void expiredQueuedTaskNeverStarts() throws Exception {
        List<Runnable> queue=new CopyOnWriteArrayList<>(); var executed=new AtomicBoolean();
        try(var supervisor=new TurnTaskSupervisor(queue::add)) {
            var ctx=context("s","queued",50); supervisor.submit(ctx,()->executed.set(true));
            verify(ctx.getSink(),timeout(2000)).error(contains("TURN_TIMEOUT"));
            queue.get(0).run(); assertFalse(executed.get()); assertEquals(0,supervisor.activeCount());
        }
    }
    @Test void replacingSessionInterruptsOldTaskWithoutCancellingNewTurn() throws Exception {
        var workers=Executors.newFixedThreadPool(2); var firstStarted=new CountDownLatch(1); var firstStopped=new CountDownLatch(1);
        var newStarted=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var supervisor=new TurnTaskSupervisor(workers)) {
            var old=context("s","old",5000); var next=context("s","new",5000);
            supervisor.submit(old,()-> { firstStarted.countDown(); try { release.await(); } catch(InterruptedException ex) { firstStopped.countDown(); } });
            assertTrue(firstStarted.await(1,TimeUnit.SECONDS));
            supervisor.submit(next,()-> { newStarted.countDown(); try { release.await(); } catch(InterruptedException ignored) { } });
            assertTrue(firstStopped.await(1,TimeUnit.SECONDS)); assertTrue(newStarted.await(1,TimeUnit.SECONDS));
            assertTrue(old.isCancelled()); assertFalse(next.isCancelled()); assertEquals(1,supervisor.activeCount());
            assertTrue(supervisor.cancel("s")); assertTrue(next.isCancelled());
        } finally { release.countDown(); workers.shutdownNow(); }
    }
}
