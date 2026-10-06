package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;

/** Tests the resumable runtime ABI; this does not claim source-frame lowering. */
@Timeout(20)
final class ActorTaskChannelCombinationTest {
    enum Operation { READ, WRITE, SELECT_READ, SELECT_WRITE }

    @TestFactory
    Stream<DynamicTest> pendingOperationsUnwindAndResumeOnSharedAndPrivateActorSchedulers() {
        return List.of(false, true).stream().flatMap(isolated ->
                java.util.Arrays.stream(Operation.values()).map(operation ->
                        DynamicTest.dynamicTest((isolated ? "PRIVATE/" : "SHARED/") + operation,
                                () -> provePendingOperation(isolated, operation))));
    }

    private void provePendingOperation(boolean isolated, Operation operation) throws Exception {
        String combination = (isolated ? "PRIVATE/" : "SHARED/") + operation;
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
            ChannelRuntime.Channel<Integer> loser = new ChannelRuntime.Channel<>(0);
            AtomicReference<OresFuture<Object>> result = new AtomicReference<>();
            AtomicReference<OresFuture<?>> registration = new AtomicReference<>();
            AtomicReference<Thread> carrier = new AtomicReference<>();
            AtomicLong dispatch = new AtomicLong();
            AtomicInteger steps = new AtomicInteger();
            CountDownLatch pending = new CountDownLatch(1);
            CountDownLatch peerRan = new CountDownLatch(1);
            Thread completingThread = Thread.currentThread();

            ActorRuntime.BehaviorFactory<String> factory = ignored -> (message, context) -> {
                result.set(runtime.startActorTask(resume -> {
                    assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow(), combination);
                    assertTrue(ActorRuntime.isActorCarrierThread(), combination);
                    assertNotNull(OresScheduler.current(), combination);
                    if (steps.getAndIncrement() == 0) {
                        carrier.set(Thread.currentThread());
                        dispatch.set(OresScheduler.currentDispatchId());
                        OresFuture<?> wait = switch (operation) {
                            case READ -> channel.readAsync();
                            case WRITE -> channel.writeAsync(42);
                            case SELECT_READ -> ChannelRuntime.SelectSet.of(
                                    ChannelRuntime.read(channel), ChannelRuntime.read(loser)).selectAsync();
                            case SELECT_WRITE -> ChannelRuntime.SelectSet.of(
                                    ChannelRuntime.write(channel, 42), ChannelRuntime.write(loser, 99)).selectAsync();
                        };
                        runtime.ownCurrentActorFuture(wait);
                        registration.set(wait);
                        assertFalse(wait.isDone(), combination + " must genuinely suspend");
                        pending.countDown();
                        return OresScheduler.await(wait);
                    }
                    assertNotEquals(dispatch.get(), OresScheduler.currentDispatchId(), combination);
                    assertSame(carrier.get(), Thread.currentThread(), combination);
                    assertNotSame(completingThread, Thread.currentThread(), combination);
                    if (operation == Operation.READ) assertEquals(42, resume.value(), combination);
                    if (operation == Operation.WRITE) assertNull(resume.value(), combination);
                    if (operation == Operation.SELECT_READ || operation == Operation.SELECT_WRITE) {
                        ChannelRuntime.SelectResult selection = assertInstanceOf(
                                ChannelRuntime.SelectResult.class, resume.value(), combination);
                        assertEquals(0, selection.index(), combination);
                        assertEquals(operation == Operation.SELECT_READ
                                ? ChannelRuntime.SelectOperation.READ : ChannelRuntime.SelectOperation.WRITE,
                                selection.operation(), combination);
                        if (operation == Operation.SELECT_READ) assertEquals(42, selection.value(), combination);
                    }
                    return OresScheduler.done(resume.value());
                }));
            };
            ActorRuntime.ActorRef<String> waiting = isolated
                    ? runtime.spawnPrivateTrusted(factory) : runtime.spawnSharedTrusted(factory);
            ActorRuntime.BehaviorFactory<String> peerFactory = ignored -> (message, context) -> peerRan.countDown();
            ActorRuntime.ActorRef<String> peer = isolated
                    ? runtime.spawnPrivateTrusted(peerFactory) : runtime.spawnSharedTrusted(peerFactory);
            waiting.send("arm");
            assertTrue(pending.await(2, TimeUnit.SECONDS), combination);
            peer.send("progress while suspended");
            assertTrue(peerRan.await(2, TimeUnit.SECONDS), combination + " retained the only carrier");
            assertFalse(registration.get().isDone(), combination);
            if (operation == Operation.READ || operation == Operation.SELECT_READ) {
                assertTrue(channel.tryWrite(42), combination);
            } else {
                assertEquals(42, channel.readAsync().get(2, TimeUnit.SECONDS), combination);
            }
            result.get().get(2, TimeUnit.SECONDS); // propagates assertions from actor task to JUnit
            assertEquals(2, steps.get(), combination);
            assertFalse(loser.tryWrite(7), "losing read registration must detach");
            assertTrue(loser.tryRead().isEmpty(), "losing write must not commit");
            waiting.stop();
            peer.stop();
            assertTrue(waiting.awaitTermination(2, TimeUnit.SECONDS), combination);
            assertTrue(peer.awaitTermination(2, TimeUnit.SECONDS), combination);
            assertTrue(waiting.failure().isEmpty(), () -> combination + " failure: " + waiting.failure());
            assertTrue(peer.failure().isEmpty(), () -> combination + " peer failure: " + peer.failure());
        }
    }

    @Test
    void cancellingActorTaskWithdrawsSelectWriteAndPreventsLateResume() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
            AtomicReference<OresFuture<Object>> task = new AtomicReference<>();
            AtomicReference<OresFuture<ChannelRuntime.SelectResult>> selection = new AtomicReference<>();
            AtomicInteger steps = new AtomicInteger();
            CountDownLatch pending = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                task.set(runtime.startActorTask(resume -> {
                    if (steps.getAndIncrement() == 0) {
                        selection.set(runtime.ownCurrentActorFuture(
                                ChannelRuntime.SelectSet.of(ChannelRuntime.write(channel, 42)).selectAsync()));
                        pending.countDown();
                        return OresScheduler.await(selection.get());
                    }
                    fail("cancelled actor task resumed");
                    return OresScheduler.done(null);
                }));
            });
            actor.send("arm");
            assertTrue(pending.await(2, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(task.get().isCancelled());
            assertTrue(selection.get().isCancelled());
            assertTrue(channel.tryRead().isEmpty(), "cancelled selection must not deliver its payload");
            OresFuture<Integer> liveReader = channel.readAsync();
            assertTrue(channel.tryWrite(99));
            assertEquals(99, liveReader.get(2, TimeUnit.SECONDS));
            assertEquals(1, steps.get());
        }
    }
}
