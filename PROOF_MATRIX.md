# Concurrency proof matrix

Pinned source snapshot:

`ores-truffle-oreslang/oreslang-source.java@9bcbf593b1312b9cb92c01d481618760a37731d9`

This is the PR #269 concurrency-contract head, stacked directly on the ActorGroup event-bus head and including the channel/select/mailbox work from PR #252.

## Executable proofs

| Area | Proof | Status |
|---|---|---|
| Future + scheduler | Awaiting a channel Future unwinds and resumes through a fresh owning-scheduler dispatch | executable |
| Producer isolation | Channel/Future completion thread never executes guest continuation code | executable |
| Mailbox/channel unification | ActorCell mailbox transport is `ChannelRuntime.Channel<MessageEnvelope>` | executable |
| `nb readch` lowering substrate | Pending channel Future can re-enter the actor through a runtime continuation envelope | executable |
| `nb writech` Future surface | `writeAsync` returns a pending `OresFuture<void>` until a reader commits | executable |
| `nb writech` callback substrate | The same write registration can enqueue a callback back through the actor mailbox/scheduler lease | executable |
| select / nb select | `ChannelRuntime.SelectSet.selectAsync` is the one registration substrate; source parser supports static/dynamic nb select | executable + syntax proof |
| ActorGroup event bus | Subscription is channel-backed; event Future completion re-enters subscriber actor through mailbox continuation | executable |
| Shared actors | Shared actor progresses on SHARED dispatcher | executable |
| Iso/private actors | Private actor progresses on separate PRIVATE dispatcher | executable |
| Hungry actors | Dedicated native carrier does not consume/starve ordinary actor dispatcher | executable |
| Cancellation | Actor cancellation detaches callback continuations and bare actor-owned nonblocking Futures; late completion cannot resurrect actor | executable |
| Event publisher isolation | Event publisher never runs subscriber guest callback inline | executable |

## Contract gaps this snapshot must not pretend are solved

### 1. Pending blocking source waits inside actors

PR #252 explicitly says arbitrary pending blocking actor `await`, `readch`, `writech`, and `select` still need source-frame lowering onto the resumable `OresScheduler.Task` ABI.

Already-ready operations work. A genuinely pending blocking operation currently fails closed rather than parking an actor carrier. This is safe, but it is not the final semantics.

**Required proof before merge:** a source-level actor suspends on each pending operation, another actor continues on the same bounded dispatcher, and the suspended actor later resumes on a fresh dispatch without retaining a Java interpreter stack.

### 2. `nb cb writech` syntax — proven in this snapshot

The PR #269 snapshot implements and tests both `nb writech` -> `Future<void>` and the void `nb cb writech ... || -> { ... }` callback surface. The callback path is required to re-enter through the owning actor scheduler lease rather than execute inline on the producer/channel-completion thread.

### 3. Untrusted actors — partial runtime proof; hard-isolate boundary still open

The pinned #269 head now has first-class `UNTRUSTED` actor syntax, zero-capability admission, data-only inbound messaging, a separate untrusted dispatcher, CPU/fuel safepoints, and structured force-cancellation APIs. `UntrustedActorTest` and `ActorStructuredCancellationTest` exercise those contracts.

**Still required before claiming the high-assurance model complete:** execute untrusted actors inside the intended independently revocable Graal/Wasm isolate (or stronger external-process boundary) and prove host force-cancel revokes that physical isolation boundary before logical teardown.

### 4. ActorMailman

The pinned runtime has ActorGroups and the event bus, but no `ActorMailman` implementation/class or group outbox/mailman event loop in this stack.

Do not claim Mailman correctness from ActorGroup/event-bus tests. Converge the Mailman branch first, then prove:

- one logical serialized mailman per group;
- bounded MPSC group outbox;
- mailman runs on CONTROL pool, not one scheduler per actor;
- mailman never scans/selects every actor mailbox;
- mailbox writes enqueue runnable actors;
- cancellation/teardown cannot strand outbox entries.

### 5. Persistent `actor` classes / full spawn syntax convergence

The active actor-class hardening work is on a separate stack. This harness currently proves the runtime actor substrate (`spawnShared`, `spawnPrivate`) and source actor-callable/channel syntax, not the final persistent `define actor Worker as ... end` object-model integration.

## Acceptance rule

A feature moves from **contract gap** to **proven** only when:

1. there is an executable test here;
2. it uses the real pinned runtime/compiler implementation, not a mock;
3. it proves scheduler/carrier ownership, not only output values;
4. cancellation and late-completion behavior are covered where applicable;
5. bounded-resource behavior is covered (mailbox/channel/outbox/backpressure);
6. the proof runs in CI on a clean checkout.
