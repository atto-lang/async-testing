# Oreslang Async / Actor / Channel Proof Harness

This repository is an executable integration harness for the Oreslang concurrency stack.

It is intentionally designed to prove cross-layer invariants involving:

- OresFuture / Awaitable / async-await scheduler resumption
- actor spawn and actor execution domains
- shared, private/iso, untrusted, and hungry actors
- ActorGroup / ActorMailman routing
- mailbox = policy around Channel<Envelope>
- readch / writech / nb readch / nb writech
- static and dynamic select / nb select
- ActorGroup event bus delivery and backpressure
- cancellation, fairness, progress, and carrier starvation safety

The first proof branch is pinned to the active Oreslang source stack that includes
PR #252 (channels/select/mailbox/cancellation) and PR #255 (ActorGroup event bus).

Source snapshot: `ores-truffle-oreslang/oreslang-source.java@e1e84d071beebb59671eeae28e2344f9a6bc3940`.

See `PROOF_MATRIX.md` on the proof branch for the required invariants and known gaps.

Run the native carrier build and proof suite with `mvn clean test` (JDK 21 or 25,
plus a C compiler on Linux/macOS). CI covers both platforms and JDK versions.

The pinned snapshot includes local fixes for Darwin pthread declarations,
closed-mailbox/subscription teardown, aggregation of void Future results, and
the `Future<void>` channel-write type. The proof tests use bounded waits and
explicitly arm the rendezvous read before starting its producer.

The suite now contains 52 test invocations with no disabled tests, including five
checks that unsupported operations fail closed. Coverage combines guest source
execution with runtime proofs of dispatch ownership, bounded queues, exact
channel/select delivery, actor-group fan-out and event-bus delivery policies.

The callback write form is implemented for actor execution domains:

```ores
val Future<void> pending = nb writech output, value;
nb cb writech output, value || -> { stdio.println("write completed"); };
```

Callback writes reuse static nb select lowering. Actor callables remain alive
until their registered continuations drain, and deferred failures reach the
caller. Shared Future registrations are counted separately to avoid dropping a
second callback during teardown.

Mailman, untrusted actors, pending blocking source actor waits, isolated event-bus
subscriptions, full persistent actor-class/spawn syntax, and root callback-write
lowering remain explicit gaps in `PROOF_MATRIX.md`.
