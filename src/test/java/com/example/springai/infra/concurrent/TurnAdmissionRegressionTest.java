package com.example.springai.infra.concurrent;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.event.CancellableAgentEventSink;
import com.example.springai.core.event.SsePacket;
import com.example.springai.infra.sse.SseAgentEventSink;
import com.example.springai.infra.sse.SseEmitterManager;
import com.example.springai.infra.sse.SseEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class TurnAdmissionRegressionTest {
    private final List<SsePacket<?>> packets = new CopyOnWriteArrayList<>();
    private final SseEmitterManager transport = mock(SseEmitterManager.class);
    private final SseEventPublisher publisher = new SseEventPublisher(transport);

    private Fixture fixture(String session, String turn, long timeoutMs) {
        when(transport.send(anyString(), any())).thenAnswer(call -> {
            packets.add(call.getArgument(1));
            return true;
        });
        var context = AgentContext.builder().sessionId(session).turnId(turn)
                .deadlineMillis(System.currentTimeMillis() + timeoutMs).build();
        var sink = new SseAgentEventSink(session, turn, publisher, null, null, null);
        context.setSink(new CancellableAgentEventSink(context, sink));
        return new Fixture(context, sink);
    }

    private record Fixture(AgentContext context, SseAgentEventSink sink) {
        void submit(TurnTaskSupervisor supervisor, Runnable task) {
            supervisor.submit(context, task, sink::activate, sink::release);
        }
    }

    @Test
    void constructingUnacceptedSinkDoesNotStealCurrentBinding() {
        List<Runnable> queue = new CopyOnWriteArrayList<>();
        try (var supervisor = new TurnTaskSupervisor(queue::add)) {
            var old = fixture("s", "old", 5000);
            old.submit(supervisor, () -> { });
            var next = fixture("s", "next", 5000);
            assertFalse(publisher.isStaleTurn("s", "old"));
            assertFalse(next.sink.message("unaccepted"));
            assertTrue(packets.isEmpty());
        }
    }

    @Test
    void fullAdmissionPreservesOldTaskBindingAndItsDoneEvent() {
        List<Runnable> queue = new CopyOnWriteArrayList<>();
        try (var supervisor = new TurnTaskSupervisor(queue::add, 1)) {
            var old = fixture("s", "old", 5000);
            old.submit(supervisor, () -> {
                assertTrue(old.context.getSink().message("old reply"));
                assertTrue(old.context.getSink().done());
            });
            var next = fixture("s", "next", 5000);
            assertThrows(RejectedExecutionException.class, () -> next.submit(supervisor, () -> fail("rejected task ran")));
            assertFalse(old.context.isCancelled());
            assertFalse(publisher.isStaleTurn("s", "old"));
            queue.get(0).run();
            assertEquals(List.of("message", "done"), packets.stream().map(SsePacket::getEvent).toList());
            packets.forEach(packet -> assertEquals("old", ((Map<?, ?>) packet.getData()).get("turnId")));
            assertEquals(0, supervisor.activeCount());
            assertEquals(0, publisher.activeTurnCount());
            assertEquals(1, supervisor.rejectedCount());
        }
    }

    @Test
    void workerRejectionAlsoPreservesPreviousRunAndDoesNotLeakAdmission() {
        List<Runnable> queue = new CopyOnWriteArrayList<>();
        AtomicBoolean reject = new AtomicBoolean();
        Executor executor = task -> {
            if (reject.get()) throw new RejectedExecutionException("worker full");
            queue.add(task);
        };
        try (var supervisor = new TurnTaskSupervisor(executor, 2)) {
            var old = fixture("s", "old", 5000);
            old.submit(supervisor, () -> { });
            reject.set(true);
            for (int i = 0; i < 3; i++) {
                var next = fixture("s", "rejected-" + i, 5000);
                assertThrows(RejectedExecutionException.class, () -> next.submit(supervisor, () -> fail("rejected task ran")));
                assertFalse(old.context.isCancelled());
                assertFalse(publisher.isStaleTurn("s", "old"));
                assertEquals(1, supervisor.activeCount());
            }
            reject.set(false);
            var accepted = fixture("another-session", "accepted", 5000);
            accepted.submit(supervisor, () -> { });
            assertEquals(2, supervisor.activeCount());
        }
    }

    @Test
    void constructionAndSubmissionInOppositeOrderHaveOneConsistentWinner() {
        List<Runnable> queue = new CopyOnWriteArrayList<>();
        try (var supervisor = new TurnTaskSupervisor(queue::add)) {
            var firstConstructed = fixture("s", "A", 5000);
            var secondConstructed = fixture("s", "B", 5000);
            secondConstructed.submit(supervisor, () -> fail("superseded B ran"));
            firstConstructed.submit(supervisor, () -> {
                assertTrue(firstConstructed.context.getSink().message("A reply"));
                assertTrue(firstConstructed.context.getSink().done());
            });
            assertTrue(secondConstructed.context.isCancelled());
            assertFalse(firstConstructed.context.isCancelled());
            assertFalse(publisher.isStaleTurn("s", "A"));
            assertFalse(supervisor.cancel("s", "B"));
            // Force the cancelled dispatch to arrive after A's registration.
            queue.get(0).run();
            assertFalse(publisher.isStaleTurn("s", "A"));
            queue.get(1).run();
            assertEquals(1, packets.stream().filter(p -> p.getEvent().equals("done")).count());
            assertEquals(0, supervisor.activeCount());
            assertEquals(0, publisher.activeTurnCount());
        }
    }

    @Test
    void workerCannotRunBeforeActivationCommit() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch enteredExecute = new CountDownLatch(1);
        CountDownLatch permitAcceptance = new CountDownLatch(1);
        CountDownLatch userCode = new CountDownLatch(1);
        Executor pausedAcceptance = task -> {
            workers.execute(task);
            enteredExecute.countDown();
            try { assertTrue(permitAcceptance.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RejectedExecutionException(ex); }
        };
        try (var supervisor = new TurnTaskSupervisor(pausedAcceptance)) {
            var next = fixture("s", "A", 5000);
            Future<?> submit = caller.submit(() -> next.submit(supervisor, () -> {
                assertFalse(publisher.isStaleTurn("s", "A"));
                userCode.countDown();
            }));
            assertTrue(enteredExecute.await(1, TimeUnit.SECONDS));
            assertEquals(1, userCode.getCount());
            assertEquals(0, publisher.activeTurnCount());
            permitAcceptance.countDown();
            submit.get(2, TimeUnit.SECONDS);
            assertTrue(userCode.await(2, TimeUnit.SECONDS));
        } finally {
            permitAcceptance.countDown();
            caller.shutdownNow();
            workers.shutdownNow();
        }
    }

    @Test
    void inlineExecutorDoesNotRunBusinessCodeUnderLifecycleLock() throws Exception {
        ExecutorService otherCaller = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var supervisor = new TurnTaskSupervisor(Runnable::run)) {
            var first = fixture("s1", "A", 5000);
            var second = fixture("s2", "B", 5000);
            first.submit(supervisor, () -> {
                try {
                    otherCaller.submit(() -> second.submit(supervisor, () -> { })).get(2, TimeUnit.SECONDS);
                } catch (Throwable ex) { failure.set(ex); }
            });
            assertNull(failure.get(), "another session was blocked by inline business work");
            assertEquals(0, supervisor.activeCount());
            assertEquals(0, publisher.activeTurnCount());
        } finally { otherCaller.shutdownNow(); }
    }

    @Test
    void queuedExpiryReleasesBindingAndNeverStartsUserCode() throws Exception {
        List<Runnable> queue = new CopyOnWriteArrayList<>();
        CountDownLatch released = new CountDownLatch(1);
        try (var supervisor = new TurnTaskSupervisor(queue::add)) {
            var turn = fixture("s", "expired", 50);
            supervisor.submit(turn.context, () -> fail("expired user code ran"), turn.sink::activate, () -> {
                turn.sink.release(); released.countDown();
            });
            assertTrue(released.await(2, TimeUnit.SECONDS));
            queue.get(0).run();
            assertEquals(0, publisher.activeTurnCount());
            assertEquals(0, supervisor.activeCount());
            assertEquals(1, supervisor.timeoutCount());
            assertEquals(1, packets.stream().filter(p -> p.getEvent().equals("error")).count());
        }
    }

    @Test
    void duplicateActiveTurnIsRejectedWithoutCancellingIt() {
        List<Runnable> queue = new CopyOnWriteArrayList<>();
        try (var supervisor = new TurnTaskSupervisor(queue::add)) {
            var first = fixture("s", "A", 5000);
            first.submit(supervisor, () -> { });
            var duplicate = fixture("s", "A", 5000);
            assertThrows(IllegalArgumentException.class, () -> duplicate.submit(supervisor, () -> { }));
            assertFalse(first.context.isCancelled());
            assertFalse(supervisor.cancel("s", "old"));
            assertTrue(supervisor.cancel("s", "A"));
            assertTrue(first.context.isCancelled());
        }
    }
}
