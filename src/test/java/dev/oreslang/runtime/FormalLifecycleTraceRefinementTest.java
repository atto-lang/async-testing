package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Concrete trace refinement for the GC and structured-cancellation formal
 * models in oreslang-source.java.
 */
@Timeout(15)
final class FormalLifecycleTraceRefinementTest {
    @Test
    void actorDomainRetirementCleansReachableOwnerExactlyOnce() throws Exception {
        AtomicInteger cleanups = new AtomicInteger();
        AtomicReference<Object> staleHostOwner = new AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(1);

        try (RuntimeGarbageCollector gc =
                        new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1));
             ActorRuntime runtime = new ActorRuntime()) {
            runtime.setActorExitHook(gc::retireActorDomain);

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        Object owner = new Object();
                        staleHostOwner.set(owner);
                        gc.track(owner, cleanups::incrementAndGet);
                        registered.countDown();
                        context.self().stop();
                    });

            actor.send("register-and-stop");
            assertTrue(registered.await(2, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(staleHostOwner.get() != null,
                    "test deliberately keeps the owner strongly reachable");
            assertEquals(1, cleanups.get(),
                    "actor-domain retirement, not host reachability, owns actor resource lifetime");

            gc.collectPeriodic();
            assertEquals(1, cleanups.get(),
                    "later process sweeps cannot double-clean the retired actor resource");
        }
    }

    @Test
    void failedExplicitCleanupRefinesRetryableOpenRegistryState() {
        AtomicInteger attempts = new AtomicInteger();

        try (RuntimeGarbageCollector gc =
                new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            RuntimeGarbageCollector.CleanupHandle handle =
                    gc.track(new Object(), () -> {
                        if (attempts.incrementAndGet() == 1) {
                            throw new IllegalStateException("transient");
                        }
                    });

            assertThrows(IllegalStateException.class, handle::close);
            RuntimeGarbageCollector.CollectionReport retry = gc.collectPeriodic();

            assertEquals(1, retry.cleaned());
            assertEquals(0, retry.trackedAfter());
            assertEquals(2, attempts.get());
            handle.close();
            assertEquals(2, attempts.get(),
                    "successful retry makes later explicit release idempotent");
        }
    }

    @Test
    void gracefulRootStopTerminatesStructuredTreeBottomUpWithoutOrphans()
            throws Exception {
        AtomicReference<ActorRuntime.ActorHandle<String>> childRef =
                new AtomicReference<>();
        AtomicReference<ActorRuntime.ActorHandle<String>> grandchildRef =
                new AtomicReference<>();
        CountDownLatch grandchildCreated = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> root =
                    runtime.spawnShared(() -> (message, context) -> {
                        if ("spawn".equals(message)) {
                            ActorRuntime.ActorHandle<String> child =
                                    context.runtime().spawnChildShared(
                                            factoryContext ->
                                                    (childMessage, childContext) -> {
                                                        if ("spawn-grandchild".equals(childMessage)) {
                                                            grandchildRef.set(
                                                                    childContext.runtime()
                                                                            .spawnChildShared(
                                                                                    grandFactory ->
                                                                                            (grandMessage, grandContext) -> { }));
                                                            grandchildCreated.countDown();
                                                        }
                                                    });
                            childRef.set(child);
                            child.send("spawn-grandchild");
                            return;
                        }

                        if ("stop".equals(message)) {
                            context.self().stop();
                        }
                    });

            root.send("spawn");
            assertTrue(grandchildCreated.await(2, TimeUnit.SECONDS));

            root.send("stop");
            assertTrue(root.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(childRef.get().awaitTermination(1, TimeUnit.SECONDS));
            assertTrue(grandchildRef.get().awaitTermination(1, TimeUnit.SECONDS));

            assertTrue(root.failure().isEmpty(),
                    "graceful root stop remains a normal root termination");
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    childRef.get().failure().orElseThrow());
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    grandchildRef.get().failure().orElseThrow());
        }
    }

    @Test
    void childLifecycleAuthorityCannotCancelItsParent() throws Exception {
        CountDownLatch denied = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> root =
                    runtime.spawnShared(() -> (message, context) -> {
                        if ("spawn".equals(message)) {
                            ActorRuntime.ActorHandle<Object> child =
                                    context.runtime().spawnChildShared(
                                            factoryContext ->
                                                    (childMessage, childContext) -> {
                                                        if (childMessage
                                                                instanceof ActorRuntime.ActorRef<?> parent) {
                                                            try {
                                                                parent.cancel();
                                                            } catch (SecurityException expected) {
                                                                denied.countDown();
                                                                childContext.self().stop();
                                                            }
                                                        }
                                                    });
                            child.send(context.self());
                            return;
                        }

                        if ("stop".equals(message)) context.self().stop();
                    });

            root.send("spawn");
            assertTrue(denied.await(2, TimeUnit.SECONDS));
            assertTrue(root.isAlive(),
                    "denied upward lifecycle authority must leave the parent alive");
            root.send("stop");
            assertTrue(root.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(root.failure().isEmpty());
        }
    }

    @Test
    void forceKillRevokesTheCompleteThreeNodeSubtreeAtomically() throws Exception {
        AtomicReference<ActorRuntime.ForceCancellationRequest> observed =
                new AtomicReference<>();
        AtomicReference<ActorRuntime.ActorHandle<String>> childRef =
                new AtomicReference<>();
        AtomicReference<ActorRuntime.ActorHandle<String>> grandchildRef =
                new AtomicReference<>();
        CountDownLatch grandchildCreated = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            runtime.setForceCancellationTreeHook(request -> {
                observed.set(request);
                return true;
            });

            ActorRuntime.ActorRef<String> root =
                    runtime.spawnShared(() -> (message, context) -> {
                        ActorRuntime.ActorHandle<String> child =
                                context.runtime().spawnChildShared(
                                        factoryContext ->
                                                (childMessage, childContext) -> {
                                                    if ("spawn-grandchild".equals(childMessage)) {
                                                        grandchildRef.set(
                                                                childContext.runtime()
                                                                        .spawnChildShared(
                                                                                grandFactory ->
                                                                                        (grandMessage, grandContext) -> { }));
                                                        grandchildCreated.countDown();
                                                    }
                                                });
                        childRef.set(child);
                        child.send("spawn-grandchild");
                    });

            root.send("spawn");
            assertTrue(grandchildCreated.await(2, TimeUnit.SECONDS));

            ActorRuntime.ActorControlHandle control = runtime.controlHandle(root);
            assertTrue(control.kill());

            assertEquals(3, observed.get().targets().size(),
                    "force revoker must receive the complete structured subtree");
            assertTrue(control.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(childRef.get().awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(grandchildRef.get().awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    control.failure().orElseThrow());
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    childRef.get().failure().orElseThrow());
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    grandchildRef.get().failure().orElseThrow());
        }
    }
}
