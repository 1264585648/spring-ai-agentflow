package com.example.springai.infra.concurrent;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.event.AgentEventSink;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(5)
class TurnTaskSupervisorTest {
    @Test void totalDeadlineActuallyInterruptsBusyWorker() throws Exception {
        ExecutorService workers=Executors.newSingleThreadExecutor();
        CountDownLatch started=new CountDownLatch(1),interrupted=new CountDownLatch(1);
        AtomicInteger errors=new AtomicInteger();
        try(var supervisor=new TurnTaskSupervisor(workers)) {
            var context=context(100,errors);
            supervisor.submit(context,() -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch(InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(1,TimeUnit.SECONDS));
            assertTrue(interrupted.await(1,TimeUnit.SECONDS));
            await(() -> supervisor.activeCount()==0);
            assertTrue(context.isCancelled()); assertEquals(1,supervisor.timeoutCount());
            await(() -> errors.get()==1);
        } finally { workers.shutdownNow(); }
    }
    @Test void deadlineCancelsQueuedTaskBeforeExecutionBegins() throws Exception {
        CopyOnWriteArrayList<Runnable> queue=new CopyOnWriteArrayList<>(); AtomicInteger entered=new AtomicInteger();
        try(var supervisor=new TurnTaskSupervisor(queue::add)) {
            var context=context(30,new AtomicInteger()); supervisor.submit(context,entered::incrementAndGet);
            await(() -> supervisor.timeoutCount()==1);
            queue.forEach(Runnable::run);
            assertEquals(0,entered.get()); assertEquals(0,supervisor.activeCount()); assertTrue(context.isCancelled());
        }
    }
    @RepeatedTest(20)
    void supersedingTurnInterruptsOldWorkerAndDoesNotRemoveReplacement() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(2);
        CountDownLatch started=new CountDownLatch(1),interrupted=new CountDownLatch(1),holdNew=new CountDownLatch(1);
        CountDownLatch newStarted=new CountDownLatch(1),oldExited=new CountDownLatch(1);
        AtomicBoolean newInterrupted=new AtomicBoolean();
        AtomicReference<Thread> oldWorker=new AtomicReference<>();
        AtomicReference<Throwable> oldFailure=new AtomicReference<>();
        long began=System.nanoTime();
        try(var supervisor=new TurnTaskSupervisor(workers)) {
            var first=context(5000,new AtomicInteger());
            supervisor.submit(first,() -> {
                oldWorker.set(Thread.currentThread());
                started.countDown();
                try { Thread.sleep(5000); }
                catch(InterruptedException ex) { interrupted.countDown(); }
                catch(Throwable ex) { oldFailure.set(ex); throw ex; }
                finally { oldExited.countDown(); }
            });
            assertTrue(started.await(1,TimeUnit.SECONDS));
            int before=supervisor.activeCount();
            var second=context(5000,new AtomicInteger());
            supervisor.submit(second,() -> {
                newStarted.countDown();
                try{holdNew.await();}catch(InterruptedException ex){newInterrupted.set(true);}
            });
            boolean gotInterrupt=interrupted.await(1,TimeUnit.SECONDS);
            if(!gotInterrupt) {
                Thread thread=oldWorker.get();
                System.out.println("SUPERSESSION_DIAGNOSTIC elapsedMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)
                        +", activeBefore="+before+", activeAfter="+supervisor.activeCount()
                        +", oldCancelled="+first.isCancelled()+", newCancelled="+second.isCancelled()
                        +", oldExited="+(oldExited.getCount()==0)+", newStarted="+(newStarted.getCount()==0)
                        +", timeouts="+supervisor.timeoutCount()+", cancels="+supervisor.cancelledCount()
                        +", oldFailure="+oldFailure.get()+", oldThread="+thread
                        +", oldState="+(thread==null?null:thread.getState())
                        +", oldStack="+(thread==null?null:Arrays.toString(thread.getStackTrace())));
            }
            assertTrue(gotInterrupt, "Old worker must actually be interrupted");
            assertTrue(newStarted.await(1,TimeUnit.SECONDS), "Replacement must actually start");
            assertTrue(first.isCancelled());
            assertFalse(second.isCancelled());
            assertFalse(newInterrupted.get(), "Cancelling A must not interrupt B");
            assertEquals(1,supervisor.activeCount(), "Replacement is still held; timeouts=" + supervisor.timeoutCount() + ", cancelled=" + supervisor.cancelledCount());
            holdNew.countDown(); await(() -> supervisor.activeCount()==0);
        } finally { holdNew.countDown(); workers.shutdownNow(); }
    }
    private static AgentContext context(long ttl,AtomicInteger errors) {
        AgentEventSink sink=mock(AgentEventSink.class); when(sink.error(anyString())).thenAnswer(i->{errors.incrementAndGet();return true;});
        return AgentContext.builder().sessionId("s").turnId(java.util.UUID.randomUUID().toString())
                .deadlineMillis(System.currentTimeMillis()+ttl).sink(sink).build();
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!condition.getAsBoolean()&&System.nanoTime()<until) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
}
