package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class ActorGroupChannelPipelineTest {
    @Test
    void eventFanoutFlowsThroughEightActorMailboxesAndRendezvousSelectIntoScheduler() throws Exception {
        int count = 8;
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(1, 1, 16));
             OresScheduler sink = new OresScheduler(1)) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.events().defineTopic("pipeline", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            List<ActorRuntime.ActorRef<String>> actors = new ArrayList<>();
            List<AtomicReference<OresFuture<Void>>> tasks = new ArrayList<>();
            List<ChannelRuntime.SelectCase> reads = new ArrayList<>();
            CountDownLatch armed = new CountDownLatch(count);
            AtomicReference<Thread> actorCarrier = new AtomicReference<>();

            for (int i = 0; i < count; i++) {
                int index = i;
                ChannelRuntime.Channel<Integer> output = new ChannelRuntime.Channel<>(0);
                reads.add(ChannelRuntime.read(output));
                AtomicReference<OresFuture<Void>> task = new AtomicReference<>();
                tasks.add(task);
                AtomicInteger step = new AtomicInteger();
                AtomicLong firstDispatch = new AtomicLong();
                actors.add(runtime.spawnShared(() -> (message, context) -> {
                    join.join();
                    ActorEventBus.Subscription<Integer> subscription = group.events().subscribe("pipeline");
                    task.set(runtime.startActorTask(resume -> {
                        assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                        assertTrue(ActorRuntime.isActorCarrierThread());
                        Thread prior = actorCarrier.compareAndExchange(null, Thread.currentThread());
                        if (prior != null) assertSame(prior, Thread.currentThread(), "one shared carrier only");
                        int pc = step.getAndIncrement();
                        if (pc == 0) {
                            firstDispatch.set(OresScheduler.currentDispatchId());
                            OresFuture<ActorEventBus.Event<Integer>> event =
                                    runtime.ownCurrentActorFuture(subscription.readAsync());
                            assertFalse(event.isDone());
                            armed.countDown();
                            return OresScheduler.await(event);
                        }
                        assertNull(resume.failure());
                        assertNotEquals(firstDispatch.get(), OresScheduler.currentDispatchId());
                        if (pc == 1) {
                            ActorEventBus.Event<?> event = assertInstanceOf(ActorEventBus.Event.class, resume.value());
                            assertEquals(42, event.value());
                            assertEquals(1, event.sequence());
                            OresFuture<Void> written = runtime.ownCurrentActorFuture(output.writeAsync(index));
                            return OresScheduler.await(written);
                        }
                        assertEquals(2, pc);
                        subscription.close();
                        return OresScheduler.done(null);
                    }));
                }));
            }
            for (ActorRuntime.ActorRef<String> actor : actors) actor.send("subscribe");
            assertTrue(armed.await(3, TimeUnit.SECONDS), "all actors must suspend on one bounded carrier");
            assertEquals(count, group.memberCount());

            ChannelRuntime.SelectSet selection = new ChannelRuntime.SelectSet(reads);
            Set<Integer> received = new HashSet<>();
            AtomicInteger sinkStep = new AtomicInteger();
            OresFuture<Set<Integer>> drained = sink.start(resume -> {
                assertSame(sink, OresScheduler.current());
                assertFalse(ActorRuntime.inActorExecution());
                assertNotSame(actorCarrier.get(), Thread.currentThread());
                if (sinkStep.getAndIncrement() > 0) {
                    assertNull(resume.failure());
                    ChannelRuntime.SelectResult result = assertInstanceOf(ChannelRuntime.SelectResult.class, resume.value());
                    assertEquals(result.index(), result.value());
                    assertTrue(received.add((Integer) result.value()), "no duplicate delivery");
                    if (received.size() == count) return OresScheduler.done(Set.copyOf(received));
                }
                return OresScheduler.await(selection.selectAsync());
            });

            ActorEventBus.PublishReceipt receipt = group.events().publishSystem("pipeline", 42);
            assertEquals(count, receipt.subscribers());
            assertEquals(count, receipt.accepted());
            assertEquals(0, receipt.dropped());
            assertEquals(count, receipt.completion().get(3, TimeUnit.SECONDS).size());
            assertEquals(Set.of(0, 1, 2, 3, 4, 5, 6, 7), drained.get(3, TimeUnit.SECONDS));
            for (AtomicReference<OresFuture<Void>> task : tasks) task.get().get(3, TimeUnit.SECONDS);
            for (ActorRuntime.ActorRef<String> actor : actors) {
                actor.stop();
                assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(actor.failure().isEmpty());
            }
            assertEquals(0, group.memberCount());
            group.close();
            assertEquals(0, runtime.actorGroupCount());
        }
    }
}
