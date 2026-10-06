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

The proof branch is pinned to the active integrated concurrency stack through PR #269:
PR #252 (channels/select/mailbox/cancellation), PR #255 (ActorGroup event bus), and
PR #269 (VM root scheduler bootstrap, Future<void> nb writech, and nb cb writech).

Source snapshot: `ores-truffle-oreslang/oreslang-source.java@e8f874d02fd09b2fd0092691be14f23ab5be9aef`.

See `PROOF_MATRIX.md` on the proof branch for the required invariants and known gaps.
