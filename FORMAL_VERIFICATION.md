# Formal verification and trace refinement

This repo is the implementation-level half of the Oreslang formal verification strategy.

The compiler/runtime repo owns small executable state models. This repo runs the real runtime and checks that concrete traces refine those models.

## Current refinement proof

`FormalRuntimeTraceRefinementTest` exhaustively enumerates all seven non-empty readiness subsets of a three-case `select` and proves:

- a selected case was actually ready;
- the result operation is READ;
- the selected value matches the committed case;
- exactly one ready channel is consumed;
- every other ready channel remains unconsumed.

It also proves cancellation deterministically on an unready select: cancellation detaches every registration, and values written afterward remain available to ordinary readers.

This complements the existing scheduler/actor/channel proofs, which establish carrier ownership, mailbox re-entry, cancellation, bulkheads, and bounded progress.

## Why this is separate from stress testing

Stress/repetition tests are useful for finding timing bugs, but they sample executions. The formal layer instead enumerates a bounded semantic state space or a finite family of concrete readiness states. Both are required:

- **model checking**: exhaustive semantic transitions in the small model;
- **trace refinement**: real runtime behavior conforms to model invariants;
- **stress/concurrency tests**: exercise implementation timing and platform effects.

## Run

```sh
mvn -B -Dtest=FormalRuntimeTraceRefinementTest test
```

The formal/refinement tests intentionally use the same pinned runtime/compiler snapshot as the rest of this repository.


## Lifecycle refinement

`FormalLifecycleTraceRefinementTest` connects the abstract lifecycle models to
the pinned real runtime:

- actor-domain retirement cleans an actor-owned host resource even when a stale
  host reference keeps its owner reachable, and later sweeps cannot double-clean;
- a failed actor-domain retirement cleanup remains registered and is retried by
  the next process/periodic sweep;
- graceful root stop cancels a structured child/grandchild tree without orphans;
- child lifecycle authority cannot flow upward to cancel its parent;
- force kill presents the revoker with the complete three-node subtree and all
  three nodes terminate cancelled.

Ownership/proxy trace refinement remains intentionally deferred until the
allocation-domain/allocator-lowering stack is reconciled onto the runtime pinned
by this repository.


## Channel rendezvous refinement

`FormalChannelRendezvousTraceRefinementTest` checks the zero-capacity channel
model against the pinned real `ChannelRuntime`:

- read-first and write-first arrival orders both produce one handoff and no
  residual value;
- cancelling a read detaches it before a later write;
- cancelling a write prevents its old value from reaching a later reader;
- close fails pending read/write Futures and later admission is rejected without
  creating a delivery.


## Buffered channel refinement

`FormalBufferedChannelTraceRefinementTest` checks the capacity-two buffered
channel model against the pinned real `ChannelRuntime`:

- all six orderings of three distinct messages preserve FIFO delivery;
- the third immediate write is rejected while two values are buffered;
- after one read frees capacity, retrying that write succeeds;
- exactly the three admitted values are delivered exactly once;
- close rejects later writes, permits draining previously admitted buffered values, then reports closure without fabricating another delivery.
