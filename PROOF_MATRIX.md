# Concurrency proof matrix

Vendored baseline: `ores-truffle-oreslang/oreslang-source.java@e1e84d071beebb59671eeae28e2344f9a6bc3940`.
The harness includes its earlier native/teardown fixes plus callback-write parsing
and actor-callable continuation-lifetime fixes. It is not an unmodified upstream snapshot.

## Executable coverage

Run `mvn clean test` with JDK 21 or 25 and a C compiler. The suite has 52 test
invocations, zero disabled tests. Five invocations explicitly verify unsupported
boundaries (four pending blocking actor operations and isolated event delivery).
Those passing rejection checks do **not** prove the requested successful semantics.

| Combination / invariant | Executable evidence |
|---|---|
| Guest async/await + nested Futures | `ConcurrencySourceExecutionTest.asyncAwaitComposesNestedSourceFutures` |
| Guest async + channel Futures + dynamic nb select | `asyncSourceCombinesChannelFuturesWithDynamicSelect` |
| Guest nb writech returns Future<void>; nb readch returns Future<T> | `pendingNbWriteAndNbReadReturnAwaitableSourceFutures` and syntax contracts |
| Guest actor / shared actor / isoactor + callback writes | Parameterized `callbackWriteRunsAfterActorBodyOnFreshTurn`: body continues before callback |
| Guest static nb select + channel write + detached arm | `staticNbSelectArmRunsAfterBodyInsteadOfBeingDiscarded` |
| Guest blocking select/read/write with ready operations | `sourceBlockingSelectCommitsReadyWriteAndRead` |
| Shared / isoactor keywords execute guest bodies | `sharedAndIsoActorKeywordsExecuteGuestBodies` |
| 100 select readers + 100 write Futures on the same bounded scheduler | `manyPendingSelectsAndWritesProgressOnBoundedScheduler`: capacities 0/1/8 × carriers 1/2; exact payload set, fresh dispatch IDs, owning scheduler |
| Scheduler queue bound and overflow recovery | `boundedSchedulerQueueRejectsOverflowAndRecovers` |
| Mailbox is Channel<Envelope>; actor carrier isolation | Existing `ConcurrencyIntegrationProofTest` |
| 12 shared/private actors in one group, pending channel reads | `sharedAndPrivateGroupActorsResumeChannelWaitsWithoutRetainingCarriers`: one carrier per pool, all waits armed before writes, actor-ID affinity |
| 12 shared subscribers, group fan-out | `multiSharedActorGroupFanoutUsesOneCarrierAndPreservesActorLease`: exactly one callback per actor, one physical carrier |
| Actor mailbox bound, FIFO and private-pool progress during shared saturation | `boundedMailboxRejectsOverflowWhileOtherPoolProgresses` |
| Pending callable continuation lifetime and shared-pool progress | `oneShotCallableKeepsPendingContinuationAliveAndAllowsPoolProgress` |
| Multiple callbacks on the same Future | `multipleContinuationsOnSameFutureRemainOwnedUntilEveryCallbackDrains`: producer pauses between callback registrations; another actor progresses before second delivery |
| Deferred callback failure is visible to caller | `oneShotContinuationFailureIsReportedToCaller` |
| Competing selects: one commit, losing-arm detachment | `competingSelectsCommitExactlyOnceAndDetachLosingArms` |
| FAIR / PRIORITY / default select policies | `fairPriorityAndDefaultSelectHaveObservablePolicies` |
| Channel capacity/backpressure and closed reads/writes/selects | `boundedChannelBackpressureAndCloseSettleEveryWaiter` |
| Canceled channel/select waits cannot steal subsequent traffic | `cancelledReadsWritesAndSelectsCannotStealLaterTraffic` |
| Scheduler cancellation does not cancel a shared producer or revive task | `schedulerCloseCancelsTaskWithoutCancellingSharedProducer` |
| Actor cancellation detaches owned channel waits | Existing `cancellingActorDetachesOutstandingChannelContinuation` |
| Reliable, latest and lossy event policies | Parameterized `EventBusPolicyProofTest`: bounded capacity, reliable backpressure, latest coalescing, lossy drops |
| Subscriber cancellation settles backpressured publication | `subscriberCancellationSettlesBackpressuredPublicationAndClosesSubscription` |
| Event publisher cannot execute subscriber callback inline | Existing event-bus integration proof |
| Hungry native carrier does not starve ordinary private actor dispatcher | Existing `hungryActorDedicatedCarrierDoesNotStarveOrdinaryActorPool` |

## Callback and Future write contracts

```ores
val Future<void> pending = nb writech output, value;

nb cb writech output, value || -> {
  // Runs later in a serialized turn of the owning actor.
};
```

The `cb` form is a statement, not a Future expression. It lowers to one write arm
of static `nb select`, so type checking, ownership/capability traversal, transport
registration and continuation routing share the existing select machinery.
Logical-or write payloads and parenthesized channel operands are covered.
Callbacks cannot return a value or escape an enclosing loop. This snapshot
requires an actor domain for this callback form; root-scheduler callback syntax
is not supported. Plain `nb writech` retains its Future-returning behavior.

Actor-callable invocation now waits for its registered detached continuations to
drain before reporting termination. Pending-to-mailbox transfer is atomic with
idle termination, and registrations on the same Future are counted separately.
Failed callbacks propagate to the caller. Pending callbacks remain subject to the
existing wall-time deadline and structural cancellation.

## Remaining contract gaps

1. **Pending blocking guest actor waits.** `await`, `readch`, `writech`, and
   blocking `select` fail closed when genuinely pending. The parameterized source
   boundary tests execute all four failures. Ready operations and runtime
   `OresScheduler.Task` resumption pass; source-frame continuation lowering is
   still required. Ordinary guest async callables use `AsyncRuntime` virtual
   threads, whereas the bounded scheduler tests explicitly exercise the
   resumable `OresScheduler.Task` ABI. Do not infer compiler state-machine
   lowering from ordinary source async tests.
2. **ActorMailman / CONTROL event loop / group outbox.** No implementation exists
   in this baseline. Group membership and the bus do not establish Mailman
   correctness. Required next proofs: one serialized mailman per group, bounded
   MPSC outbox, CONTROL-pool ownership, mailbox writes making actors runnable,
   no per-actor scheduler or mailbox scanning, and teardown settling all outbox
   entries.
3. **Untrusted actors.** Only PRIVATE and SHARED runtime kinds exist here.
   Independent untrusted bulkheading and host isolation revocation before
   force-cancel still require the relevant upstream implementation.
4. **Private/iso actor event-bus subscriptions.** Group membership and channel
   continuations pass, but zero-copy event subscriptions are explicitly rejected.
   `privateActorsCanJoinGroupsButIsolatedEventDeliveryRemainsUnsupported` verifies
   this boundary. Isolated/copying event transport is still required.
5. **Persistent actor classes and complete source spawn syntax.** Runtime
   `spawnShared` / `spawnPrivate` and source actor callables are exercised. Final
   persistent actor-class/spawn object-model integration lives on another stack.
   Hungry actors are tested through the real native runtime API, not source syntax.
6. **Root-domain callback writes.** The new `nb cb writech` surface currently
   inherits static nb select's actor-only ownership rule. Root-scheduler callback
   lowering needs its own implementation and executable proof.

## Acceptance and CI

A feature moves from gap to proven only with real compiler/runtime execution,
ownership checks, cancellation/late-completion checks where relevant, bounded
resource coverage, and a passing clean-checkout CI run. Tests are finite evidence
of these invariants, not a formal proof of every possible interleaving.

CI runs Linux/macOS × JDK 21/25, repeats source/continuation/contention tests three
additional times, and retains JUnit reports. Local execution alone does not
establish the other platform/JDK matrix results.
