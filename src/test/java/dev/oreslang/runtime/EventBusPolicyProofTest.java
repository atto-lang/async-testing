package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(10)
final class EventBusPolicyProofTest {
    /** All subscription reads happen in their owning actor; host only sends commands. */
    private static final class Fixture implements AutoCloseable {
        final ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1));
        final ActorRuntime.ActorGroup group = runtime.createActorGroup();
        final AtomicReference<ActorEventBus.Subscription<Integer>> subscription = new AtomicReference<>();
        final AtomicReference<CompletableFuture<Integer>> readResult = new AtomicReference<>();
        final ActorRuntime.ActorRef<String> actor;

        Fixture(ActorEventBus.DeliveryPolicy policy) throws Exception {
            group.events().defineTopic("policy", policy, 1);
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            CompletableFuture<Void> ready = new CompletableFuture<>();
            actor = runtime.spawnShared(() -> (message, context) -> {
                if (message.equals("subscribe")) {
                    join.join();
                    subscription.set(group.events().subscribe("policy"));
                    ready.complete(null);
                } else {
                    CompletableFuture<Integer> result = readResult.get();
                    runtime.enqueueOnCompletion(subscription.get().readAsync(),
                            runtime.captureCurrentContinuationTarget(), (event, failure) -> {
                                if (failure != null) result.completeExceptionally(failure);
                                else result.complete(event.value());
                            });
                }
            });
            actor.send("subscribe");
            try { ready.get(2, TimeUnit.SECONDS); }
            catch (Throwable failure) { runtime.close(); throw failure; }
        }

        int read() throws Exception {
            CompletableFuture<Integer> result = new CompletableFuture<>();
            readResult.set(result);
            actor.send("read");
            return result.get(2, TimeUnit.SECONDS);
        }

        @Override public void close() { runtime.close(); }
    }

    @ParameterizedTest
    @EnumSource(ActorEventBus.DeliveryPolicy.class)
    void reliableLatestAndLossyHonorBoundedDeliveryPolicy(ActorEventBus.DeliveryPolicy policy) throws Exception {
        try (Fixture fixture = new Fixture(policy)) {
            ActorEventBus.PublishReceipt first = fixture.group.events().publishSystem("policy", 1);
            first.completion().get(2, TimeUnit.SECONDS);
            ActorEventBus.PublishReceipt second = fixture.group.events().publishSystem("policy", 2);
            assertEquals(1, fixture.subscription.get().pending(), "bounded subscription capacity");
            switch (policy) {
                case RELIABLE -> {
                    assertFalse(second.completion().isDone(), "full subscription backpressures publisher");
                    assertEquals(1, fixture.read());
                    second.completion().get(2, TimeUnit.SECONDS);
                    assertEquals(2, fixture.read());
                }
                case LATEST -> {
                    second.completion().get(2, TimeUnit.SECONDS);
                    assertEquals(1, second.coalesced());
                    assertEquals(2, fixture.read());
                }
                case LOSSY -> {
                    second.completion().get(2, TimeUnit.SECONDS);
                    assertEquals(1, second.dropped());
                    assertEquals(1, fixture.read());
                }
            }
            assertEquals(0, fixture.subscription.get().pending());
            assertTrue(fixture.actor.failure().isEmpty());
        }
    }

    @Test
    void subscriberCancellationSettlesBackpressuredPublicationAndClosesSubscription() throws Exception {
        try (Fixture fixture = new Fixture(ActorEventBus.DeliveryPolicy.RELIABLE)) {
            fixture.group.events().publishSystem("policy", 1).completion().get(2, TimeUnit.SECONDS);
            OresFuture<?> pending = fixture.group.events().publishSystem("policy", 2).completion();
            assertFalse(pending.isDone());
            assertTrue(fixture.actor.cancel());
            assertTrue(fixture.actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(fixture.subscription.get().closed());
            assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertEquals(0, fixture.group.memberCount());
        }
    }
    @Test
    void privateActorsCanJoinGroupsButIsolatedEventDeliveryRemainsUnsupported() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1))) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            group.events().defineTopic("private", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            CompletableFuture<Throwable> rejected = new CompletableFuture<>();
            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivate(() -> (message, context) -> {
                join.join();
                try {
                    group.events().subscribe("private");
                    rejected.complete(null);
                } catch (Throwable failure) { rejected.complete(failure); }
            });
            actor.send("subscribe");
            Throwable failure = rejected.get(2, TimeUnit.SECONDS);
            assertInstanceOf(SecurityException.class, failure);
            assertTrue(failure.getMessage().contains("cannot subscribe"));
            assertEquals(1, group.memberCount());
            actor.stop();
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

}
