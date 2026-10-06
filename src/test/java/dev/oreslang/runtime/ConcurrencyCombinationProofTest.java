package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Timeout(15)
final class ConcurrencyCombinationProofTest {
    private static <T> T get(OresFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest(name = "capacity={0}, scheduler carriers={1}")
    @CsvSource({"0,1", "1,1", "8,1", "0,2", "1,2", "8,2"})
    void manyPendingSelectsAndWritesProgressOnBoundedScheduler(int capacity, int carriers) throws Exception {
        try (OresScheduler scheduler = new OresScheduler(carriers, 512);
             ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(capacity)) {
            List<OresFuture<Integer>> readers = new ArrayList<>();
            List<OresFuture<Void>> writers = new ArrayList<>();
            int count = 100;
            for (int i = 0; i < count; i++) {
                AtomicLong dispatch = new AtomicLong();
                readers.add(scheduler.start(resume -> {
                    assertSame(scheduler, OresScheduler.current());
                    if (resume.initial()) {
                        dispatch.set(OresScheduler.currentDispatchId());
                        return OresScheduler.await(ChannelRuntime.SelectSet.of(
                                ChannelRuntime.read(channel)).selectAsync());
                    }
                    assertNotEquals(dispatch.get(), OresScheduler.currentDispatchId());
                    assertNull(resume.failure());
                    ChannelRuntime.SelectResult selected = (ChannelRuntime.SelectResult) resume.value();
                    assertEquals(ChannelRuntime.SelectOperation.READ, selected.operation());
                    return OresScheduler.done((Integer) selected.value());
                }));
            }
            // The same bounded scheduler must service writers while all readers are suspended.
            assertEquals(42, get(scheduler.<Integer>start(resume -> OresScheduler.done(42))));
            for (int i = 0; i < count; i++) {
                int value = i;
                writers.add(scheduler.start(resume -> {
                    assertSame(scheduler, OresScheduler.current());
                    if (resume.initial()) return OresScheduler.await(channel.writeAsync(value));
                    assertNull(resume.failure());
                    return OresScheduler.done(null);
                }));
            }
            Set<Integer> values = new HashSet<>();
            for (OresFuture<Integer> reader : readers) assertTrue(values.add(get(reader)), "duplicate delivery");
            for (OresFuture<Void> writer : writers) get(writer);
            assertEquals(count, values.size());
            for (int i = 0; i < count; i++) assertTrue(values.contains(i), "lost payload " + i);
            assertEquals(0, channel.size());
        }
    }

    @Test
    void competingSelectsCommitExactlyOnceAndDetachLosingArms() throws Exception {
        try (ChannelRuntime.Channel<Integer> left = new ChannelRuntime.Channel<>(1);
             ChannelRuntime.Channel<Integer> right = new ChannelRuntime.Channel<>(1)) {
            OresFuture<ChannelRuntime.SelectResult> first = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.read(left), ChannelRuntime.read(right)).selectAsync();
            OresFuture<ChannelRuntime.SelectResult> second = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.read(left), ChannelRuntime.read(right)).selectAsync();
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            assertTrue(left.tryWrite(10));
            assertTrue(right.tryWrite(20));
            assertEquals(Set.of(10, 20), Set.of(get(first).value(), get(second).value()));
            assertTrue(left.tryRead().isEmpty());
            assertTrue(right.tryRead().isEmpty());
            // No losing arm may retain a reader and steal these later writes.
            assertTrue(left.tryWrite(30));
            assertTrue(right.tryWrite(40));
            assertEquals(30, get(left.readAsync()));
            assertEquals(40, get(right.readAsync()));
        }
    }

    @Test
    void fairPriorityAndDefaultSelectHaveObservablePolicies() throws Exception {
        try (ChannelRuntime.Channel<Integer> left = new ChannelRuntime.Channel<>(1);
             ChannelRuntime.Channel<Integer> right = new ChannelRuntime.Channel<>(1)) {
            ChannelRuntime.SelectSet writes = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.write(left, 1), ChannelRuntime.write(right, 2));
            for (int i = 0; i < 20; i++) {
                assertEquals(i % 2, get(writes.selectAsync(ChannelRuntime.SelectPolicy.FAIR)).index());
                if (i % 2 == 0) assertEquals(1, get(left.readAsync()));
                else assertEquals(2, get(right.readAsync()));
            }
            for (int i = 0; i < 10; i++) {
                assertEquals(0, get(writes.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY)).index());
                assertEquals(1, get(left.readAsync()));
            }
            ChannelRuntime.SelectSet reads = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.read(left), ChannelRuntime.defaultCase());
            assertEquals(ChannelRuntime.SelectOperation.DEFAULT, get(reads.selectAsync()).operation());
            assertTrue(left.tryWrite(7));
            assertEquals(7, get(reads.selectAsync()).value());
        }
    }

    @Test
    void cancelledReadsWritesAndSelectsCannotStealLaterTraffic() throws Exception {
        try (ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0)) {
            assertTrue(channel.readAsync().cancel(false));
            assertTrue(channel.writeAsync(99).cancel(false));
            assertTrue(ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel)).selectAsync().cancel(false));
            assertTrue(ChannelRuntime.SelectSet.of(ChannelRuntime.write(channel, 98)).selectAsync().cancel(false));
            assertFalse(channel.tryWrite(1));
            assertTrue(channel.tryRead().isEmpty());
            OresFuture<Integer> liveRead = channel.readAsync();
            assertTrue(channel.tryWrite(42));
            assertEquals(42, get(liveRead));
        }
    }

    @Test
    void boundedChannelBackpressureAndCloseSettleEveryWaiter() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        get(channel.writeAsync(1));
        OresFuture<Void> blocked = channel.writeAsync(2);
        assertFalse(blocked.isDone());
        assertEquals(1, channel.size());
        assertEquals(1, get(channel.readAsync()));
        get(blocked);
        assertEquals(2, get(channel.readAsync()));
        OresFuture<Integer> reader = channel.readAsync();
        OresFuture<ChannelRuntime.SelectResult> select = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel)).selectAsync();
        channel.close();
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class,
                assertThrows(ExecutionException.class, () -> get(reader)).getCause());
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class,
                assertThrows(ExecutionException.class, () -> get(select)).getCause());
        try (ChannelRuntime.Channel<Integer> output = new ChannelRuntime.Channel<>(0)) {
            OresFuture<Void> writer = output.writeAsync(3);
            output.close();
            assertInstanceOf(ChannelRuntime.ChannelClosedException.class,
                    assertThrows(ExecutionException.class, () -> get(writer)).getCause());
        }
    }

    @Test
    void schedulerCloseCancelsTaskWithoutCancellingSharedProducer() throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        try (ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0)) {
            CountDownLatch armed = new CountDownLatch(1);
            AtomicInteger turns = new AtomicInteger();
            AtomicReference<OresFuture<Integer>> producer = new AtomicReference<>();
            OresFuture<Integer> task = scheduler.start(resume -> {
                turns.incrementAndGet();
                OresFuture<Integer> pending = channel.readAsync();
                producer.set(pending);
                armed.countDown();
                return OresScheduler.await(pending);
            });
            assertTrue(armed.await(2, TimeUnit.SECONDS));
            scheduler.close();
            assertTrue(task.isCancelled());
            // Await registrations detach; cancellation does not own/cancel a shared producer.
            assertFalse(producer.get().isCancelled());
            assertTrue(channel.tryWrite(99));
            assertEquals(99, get(producer.get()));
            assertEquals(1, turns.get());
            assertThrows(RejectedExecutionException.class,
                    () -> scheduler.start(resume -> OresScheduler.done(1)));
        } finally {
            scheduler.close();
        }
    }

    @Test
    void multiSharedActorGroupFanoutUsesOneCarrierAndPreservesActorLease() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1))) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.events().defineTopic("fanout", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            int count = 12;
            CountDownLatch armed = new CountDownLatch(count);
            CountDownLatch delivered = new CountDownLatch(count);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Set<ActorRuntime.ActorId> recipients = ConcurrentHashMap.newKeySet();
            Set<Thread> sharedCarriers = ConcurrentHashMap.newKeySet();
            List<ActorRuntime.ActorRef<String>> actors = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                java.util.function.Supplier<ActorRuntime.Behavior<String>> factory = () -> (message, context) -> {
                    join.join();
                    ActorEventBus.Subscription<String> sub = group.events().subscribe("fanout");
                    runtime.enqueueOnCompletion(sub.readAsync(), runtime.captureCurrentContinuationTarget(),
                            (event, error) -> {
                                try {
                                    assertNull(error);
                                    assertEquals("payload", event.value());
                                    assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                                    assertTrue(recipients.add(context.self().id()));
                                    assertEquals(ActorRuntime.ActorKind.SHARED, context.kind());
                                    sharedCarriers.add(Thread.currentThread());
                                } catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
                                finally { delivered.countDown(); }
                            });
                    armed.countDown();
                };
                actors.add(runtime.spawnShared(factory));
            }
            for (ActorRuntime.ActorRef<String> actor : actors) actor.send("subscribe");
            assertTrue(armed.await(5, TimeUnit.SECONDS));
            assertEquals(count, group.memberCount());
            ActorEventBus.PublishReceipt receipt = group.events().publishSystem("fanout", "payload");
            get(receipt.completion());
            assertEquals(count, receipt.accepted());
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(count, recipients.size());
            assertEquals(1, sharedCarriers.size(), "actors share one bounded carrier");
            for (ActorRuntime.ActorRef<String> actor : actors) {
                actor.stop();
                assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(actor.failure().isEmpty());
            }
            assertEquals(0, group.memberCount());
        }
    }
    @Test
    void sharedAndPrivateGroupActorsResumeChannelWaitsWithoutRetainingCarriers() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1))) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            CountDownLatch armed = new CountDownLatch(12);
            CountDownLatch delivered = new CountDownLatch(12);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Set<Thread> shared = ConcurrentHashMap.newKeySet();
            Set<Thread> isolated = ConcurrentHashMap.newKeySet();
            List<ActorRuntime.ActorRef<String>> actors = new ArrayList<>();
            List<ChannelRuntime.Channel<Integer>> channels = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
                channels.add(channel);
                java.util.function.Supplier<ActorRuntime.Behavior<String>> factory = () -> (message, context) -> {
                    join.join();
                    runtime.enqueueOnCompletion(channel.readAsync(), runtime.captureCurrentContinuationTarget(),
                            (value, error) -> {
                                try {
                                    assertNull(error);
                                    assertEquals(42, value);
                                    assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                                    (context.kind() == ActorRuntime.ActorKind.SHARED ? shared : isolated)
                                            .add(Thread.currentThread());
                                } catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
                                finally { delivered.countDown(); }
                            });
                    armed.countDown();
                };
                actors.add(i % 2 == 0 ? runtime.spawnShared(factory) : runtime.spawnPrivate(factory));
            }
            for (ActorRuntime.ActorRef<String> actor : actors) actor.send("arm");
            assertTrue(armed.await(5, TimeUnit.SECONDS), "12 waits cannot occupy two carriers");
            assertEquals(12, group.memberCount());
            for (ChannelRuntime.Channel<Integer> channel : channels) assertTrue(channel.tryWrite(42));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(1, shared.size());
            assertEquals(1, isolated.size());
            assertTrue(Collections.disjoint(shared, isolated));
            for (ActorRuntime.ActorRef<String> actor : actors) {
                actor.stop();
                assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(actor.failure().isEmpty());
            }
            assertEquals(0, group.memberCount());
            for (ChannelRuntime.Channel<Integer> channel : channels) channel.close();
        }
    }

    @Test
    void oneShotCallableKeepsPendingContinuationAliveAndAllowsPoolProgress() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1));
             ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
             ExecutorService host = Executors.newSingleThreadExecutor()) {
            CountDownLatch armed = new CountDownLatch(1);
            AtomicReference<Thread> callback = new AtomicReference<>();
            Future<Integer> invocation = host.submit(() -> runtime.invoke(ActorRuntime.ActorKind.SHARED,
                    "arm", (message, context) -> {
                        runtime.enqueueOnCompletion(channel.readAsync(), runtime.captureCurrentContinuationTarget(),
                                (value, error) -> {
                                    assertNull(error);
                                    assertEquals(42, value);
                                    assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                                    callback.set(Thread.currentThread());
                                });
                        armed.countDown();
                        return 7;
                    }));
            assertTrue(armed.await(2, TimeUnit.SECONDS));
            assertEquals(1, runtime.actorCount(), "callable owns a pending continuation");
            assertFalse(invocation.isDone());
            CountDownLatch ordinaryRan = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> ordinary = runtime.spawnShared(() -> (message, context) -> {
                ordinaryRan.countDown();
                context.self().stop();
            });
            ordinary.send("ping");
            assertTrue(ordinaryRan.await(2, TimeUnit.SECONDS), "pending callback must release the sole carrier");
            assertTrue(channel.tryWrite(42));
            assertEquals(7, invocation.get(2, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), callback.get());
            assertTrue(ordinary.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void oneShotContinuationFailureIsReportedToCaller() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1))) {
            IllegalStateException original = new IllegalStateException("callback failed");
            IllegalStateException observed = assertThrows(IllegalStateException.class,
                    () -> runtime.invoke(ActorRuntime.ActorKind.SHARED, "arm", (message, context) -> {
                        runtime.enqueueOnCompletion(OresFuture.completed(1),
                                runtime.captureCurrentContinuationTarget(), (value, failure) -> { throw original; });
                        return 7;
                    }));
            assertSame(original, observed);
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void boundedSchedulerQueueRejectsOverflowAndRecovers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(1, 1)) {
            CountDownLatch occupied = new CountDownLatch(1);
            OresFuture<Integer> running = scheduler.startSync(() -> {
                occupied.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                return 1;
            });
            try {
                assertTrue(occupied.await(2, TimeUnit.SECONDS));
                OresFuture<Integer> queued = scheduler.startSync(() -> 2);
                OresFuture<Integer> rejected = scheduler.startSync(() -> 3);
                assertInstanceOf(RejectedExecutionException.class,
                        assertThrows(ExecutionException.class, () -> get(rejected)).getCause());
                release.countDown();
                assertEquals(1, get(running));
                assertEquals(2, get(queued));
                assertEquals(4, get(scheduler.startSync(() -> 4)));
            } finally { release.countDown(); }
        }
    }

    @Test
    void boundedMailboxRejectsOverflowWhileOtherPoolProgresses() throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy policy = new IsolatePolicy(base.capabilities(), base.maxHeapBytes(), 2, base.maxWallTime());
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime(policy, new ActorRuntime.DispatcherConfig(1, 1, 1))) {
            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch drained = new CountDownLatch(2);
            List<Integer> values = new CopyOnWriteArrayList<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(() -> (message, context) -> {
                if (message == 0) {
                    occupied.countDown();
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } else {
                    values.add(message);
                    drained.countDown();
                }
            });
            try {
                actor.send(0);
                assertTrue(occupied.await(2, TimeUnit.SECONDS));
                actor.send(1);
                actor.send(2);
                assertThrows(IllegalStateException.class, () -> actor.send(3));
                CountDownLatch isolatedRan = new CountDownLatch(1);
                ActorRuntime.ActorRef<String> isolated = runtime.spawnPrivate(() -> (message, context) -> {
                    isolatedRan.countDown();
                    context.self().stop();
                });
                isolated.send("ping");
                assertTrue(isolatedRan.await(2, TimeUnit.SECONDS));
                release.countDown();
                assertTrue(drained.await(2, TimeUnit.SECONDS));
                assertEquals(List.of(1, 2), values);
                actor.stop();
                assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(actor.failure().isEmpty());
            } finally { release.countDown(); }
        }
    }

    @Test
    void multipleContinuationsOnSameFutureRemainOwnedUntilEveryCallbackDrains() throws Exception {
        CountDownLatch releaseSecond = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1));
             ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
             ExecutorService host = Executors.newFixedThreadPool(2)) {
            CountDownLatch armed = new CountDownLatch(1);
            CountDownLatch firstRan = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            Future<Integer> invocation = host.submit(() -> runtime.invoke(ActorRuntime.ActorKind.SHARED,
                    "arm", (message, context) -> {
                        OresFuture<Integer> pending = channel.readAsync();
                        ActorRuntime.ContinuationTarget target = runtime.captureCurrentContinuationTarget();
                        runtime.enqueueOnCompletion(pending, target, (value, failure) -> {
                            assertNull(failure);
                            calls.incrementAndGet();
                            firstRan.countDown();
                        });
                        pending.whenCompleteRuntime((value, failure) -> {
                            try { assertTrue(releaseSecond.await(5, TimeUnit.SECONDS)); }
                            catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
                        });
                        runtime.enqueueOnCompletion(pending, target, (value, failure) -> {
                            assertNull(failure);
                            calls.incrementAndGet();
                        });
                        armed.countDown();
                        return 7;
                    }));
            try {
                assertTrue(armed.await(2, TimeUnit.SECONDS));
                Future<Boolean> writer = host.submit(() -> channel.tryWrite(42));
                assertTrue(firstRan.await(2, TimeUnit.SECONDS));
                // A second actor turn proves the first callback has released the sole carrier.
                CountDownLatch progress = new CountDownLatch(1);
                ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                    progress.countDown();
                    context.self().stop();
                });
                actor.send("ping");
                assertTrue(progress.await(2, TimeUnit.SECONDS));
                assertFalse(invocation.isDone(), "second registration must still own the callable");
                releaseSecond.countDown();
                assertTrue(writer.get(2, TimeUnit.SECONDS));
                assertEquals(7, invocation.get(2, TimeUnit.SECONDS));
                assertEquals(2, calls.get());
            } finally { releaseSecond.countDown(); }
        }
    }

}
