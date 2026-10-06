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


## Channel rendezvous refinement

`FormalChannelRendezvousTraceRefinementTest` checks the zero-capacity channel
model against the pinned real `ChannelRuntime`:

- read-first and write-first arrival orders both produce one handoff and no
  residual value;
- cancelling a read detaches it before a later write;
- cancelling a write prevents its old value from reaching a later reader;
- close fails pending read/write Futures and later admission does not create a
  delivery.
