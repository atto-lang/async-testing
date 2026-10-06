package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void failedActorRetirementCleanupRetainsAProcessSweepRetryPath()
            throws Exception {
        AtomicInteger attempts = new AtomicInteger();
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
                        gc.track(owner, () -> {
                            if (attempts.incrementAndGet() == 1) {
                                throw new IllegalStateException("transient");
                            }
                        });
                        registered.countDown();
                        context.self().stop();
                    });

            actor.send("register-and-stop");
            assertTrue(registered.await(2, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(1, attempts.get(),
                    "actor retirement must have attempted cleanup once");

            RuntimeGarbageCollector.CollectionReport retry = gc.collectPeriodic();
            assertEquals(1, retry.cleaned());
            assertEquals(0, retry.trackedAfter());
            assertEquals(2, attempts.get());
            assertTrue(staleHostOwner.get() != null,
                    "retry is driven by retired-domain/retry state, not owner reachability");
        }
    }

    @Test
    void gracefulRootStopTerminatesStructuredTreeWithoutOrphans()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Tree tree = createThreeNodeTree(runtime);

            tree.root().stop();

            assertTrue(tree.root().awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(tree.child().awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(tree.grandchild().awaitTermination(2, TimeUnit.SECONDS));

            assertTrue(tree.root().failure().isEmpty(),
                    "graceful root stop remains a normal root termination");
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    tree.child().failure().orElseThrow());
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    tree.grandchild().failure().orElseThrow());
        }
    }

    @Test
    void childActorRefAuthorityCannotCancelItsParent() throws Exception {
        CountDownLatch denied = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> parent =
                    runtime.spawnShared(() -> (message, context) -> {
                        if ("spawn-child".equals(message)) {
                            ActorRuntime.ActorRef<Object> child =
                                    context.runtime().spawnShared(
                                            factoryContext ->
                                                    (childMessage, childContext) -> {
                                                        if (childMessage
                                                                instanceof ActorRuntime.ActorRef<?> parentRef) {
                                                            try {
                                                                parentRef.cancel();
                                                            } catch (SecurityException expected) {
                                                                @SuppressWarnings("unchecked")
                                                                ActorRuntime.ActorRef<Object> reply =
                                                                        (ActorRuntime.ActorRef<Object>) parentRef;
                                                                reply.send("denied");
                                                                childContext.self().stop();
                                                            }
                                                        }
                                                    });
                            child.send(context.self());
                            return;
                        }

                        if ("denied".equals(message)) {
                            denied.countDown();
                            context.self().stop();
                        }
                    });

            parent.send("spawn-child");

            assertTrue(denied.await(2, TimeUnit.SECONDS));
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(parent.failure().isEmpty(),
                    "denied upward lifecycle authority must not fail/cancel the parent");
        }
    }

    @Test
    void forceKillRevokesTheCompleteThreeNodeSubtreeAtomically()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Tree tree = createThreeNodeTree(runtime);
            AtomicReference<ActorRuntime.ForceCancellationRequest> observed =
                    new AtomicReference<>();

            runtime.setForceCancellationTreeHook(request -> {
                observed.set(request);
                return true;
            });

            assertTrue(tree.rootControl().kill());
            assertEquals(3, observed.get().targets().size(),
                    "force revoker must receive the complete structured subtree");

            assertTrue(tree.rootControl().awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(tree.child().awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(tree.grandchild().awaitTermination(2, TimeUnit.SECONDS));

            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    tree.rootControl().failure().orElseThrow());
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    tree.child().failure().orElseThrow());
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    tree.grandchild().failure().orElseThrow());
        }
    }

    private static Tree createThreeNodeTree(ActorRuntime runtime) throws Exception {
        CountDownLatch childRequestedGrandchild = new CountDownLatch(1);

        ActorRuntime.ActorRef<String> root =
                runtime.spawnShared(() -> (message, context) -> {
                    ActorRuntime.ActorHandle<String> child =
                            context.runtime().spawnChildShared(
                                    factoryContext ->
                                            (childMessage, childContext) -> {
                                                childContext.runtime()
                                                        .spawnChildShared(
                                                                grandFactoryContext ->
                                                                        (grandMessage,
                                                                         grandContext) -> { });
                                                childRequestedGrandchild.countDown();
                                            });
                    child.send("spawn-grandchild");
                });

        root.send("spawn-tree");
        assertTrue(childRequestedGrandchild.await(2, TimeUnit.SECONDS));

        ActorRuntime.ActorControlHandle rootControl = runtime.controlHandle(root);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        while (rootControl.childIds().isEmpty()
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, rootControl.childIds().size());

        ActorRuntime.ActorControlHandle child =
                runtime.controlHandle(rootControl.childIds().get(0));
        while (child.childIds().isEmpty()
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, child.childIds().size());

        ActorRuntime.ActorControlHandle grandchild =
                runtime.controlHandle(child.childIds().get(0));

        return new Tree(root, rootControl, child, grandchild);
    }

    private record Tree(
            ActorRuntime.ActorRef<String> root,
            ActorRuntime.ActorControlHandle rootControl,
            ActorRuntime.ActorControlHandle child,
            ActorRuntime.ActorControlHandle grandchild) {}
}
