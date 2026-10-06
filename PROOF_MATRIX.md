# Concurrency proof matrix

Upstream implementation is the exact Git revision in `compiler-revision.txt`,
consumed as the test-scoped `dev.oreslang:oreslang-source` Maven dependency.
`./scripts/test.sh` builds and installs that revision and its native carrier
library before running these proofs. There is no local compiler/runtime copy.

The pin follows PR #269 / `integration/concurrency-contract-20261006`, with
upstream fixes for Darwin pthread declarations, closed subscription teardown,
and aggregation of void Future results. Newer upstream isolation protections
are retained. Compiler fixes must be made upstream and adopted by changing the pin.

## Executable proofs

| Area | Proof | Status |
|---|---|---|
| Source async/await | Async `main` and nested async functions suspend on `OresFuture` and complete through the root scheduler without leaking a Future across the Polyglot boundary | executable |
| Future + scheduler | Awaiting a channel Future unwinds and resumes through a fresh owning-scheduler dispatch | executable |
| CONTROL/root event loop | OresVM prestarts CONTROL carriers; root tasks execute through that scheduler with fresh dispatch ids | executable |
| Producer isolation | Channel/Future completion thread never executes guest continuation code | executable |
| Mailbox/channel unification | ActorCell mailbox transport is `ChannelRuntime.Channel<MessageEnvelope>` | executable |
| `nb readch` | Pending channel Future can re-enter the actor through a runtime continuation envelope | executable |
| `nb writech` Future surface | Plain nonblocking write returns a pending `OresFuture<void>` until a reader commits | executable |
| `nb cb writech` callback surface | Parser/type/lowering accept the `cb` form and completion re-enters the owning actor domain instead of returning a guest Future | executable |
| `select` / `nb select` | Static/dynamic syntax, policies, read/write/default arms, actor-domain checks, and ChannelRuntime selection are exercised | executable |
| ActorGroup event bus | Subscription is channel-backed; event Future completion re-enters subscriber actor through mailbox continuation | executable |
| ActorGroup lifecycle | Creator-owned groups close before creator termination becomes externally observable | executable |
| Shared actors | Shared actor progresses on SHARED dispatcher | executable |
| Iso/private actors | Private actor progresses on separate PRIVATE dispatcher | executable |
| Untrusted actors | Source syntax + zero-capability adversarial policy + data-only transport + CPU quota + independent UNTRUSTED dispatcher progress | executable |
| Hungry actors | Dedicated native carrier does not consume/starve ordinary actor dispatcher | executable |
| Structured cancellation | Cancellation detaches outstanding channel continuations; late completion cannot resurrect actors; lifecycle authority cannot cross mailboxes | executable |
| Force-cancel boundary | Revocation callbacks are fenced from ActorRuntime re-entry and logical teardown is published only after revocation succeeds | executable runtime contract |
| Event publisher isolation | Event publisher never runs subscriber guest callback inline | executable |
| Native carriers | Actor and scheduler carrier tests execute against native carrier support on Linux/macOS | executable |

## Combined executable coverage

- Six source execution tests combine async/await, pending channel registrations,
  Future<void>, dynamic/static select, actor/isoactor callables, callback writes,
  and detached select arms. Exact output assertions detect internal Future leakage.
- Eight runtime ABI tests cross SHARED/PRIVATE with pending read/write/select-read/
  select-write. Each proves peer progress on the same one-carrier dispatcher,
  actor identity, fresh scheduler dispatch, producer isolation, exactly one
  resume, and withdrawal of losing select registrations.
- Actor-task cancellation owns and withdraws an outstanding select-write; later
  traffic must reach a live reader without delivering the cancelled payload.
- A 1,000-cycle completion/stop regression protects channel-backed mailbox
  polling against concurrent normal teardown. The pin serializes polling with
  close/drain under the actor lifecycle lock.
- Eight shared actors in one group suspend on reliable event subscriptions,
  resume through their mailboxes, write to zero-capacity channels, and are
  collected by a separate one-carrier scheduler using select. Assertions cover
  fanout receipts, actor/scheduler ownership, unique delivery, and teardown.

The group pipeline uses SHARED actors because the pinned group/event-bus policy
restricts private membership/subscription. Separate tests cover PRIVATE and
UNTRUSTED dispatchers and hungry dedicated carriers; this does not establish
cross-kind group interoperability or hungry-group integration.

Local validation on macOS arm64 / Homebrew JDK 25: 169 harness tests passed
with the matching JNI carrier library, no failures/errors/skips. Optimizing
Graal/JVMCI execution is not claimed. The broader upstream compiler suite ran
606 tests with one pre-existing mixed Java bridge failure; the same test failed
with the unmodified parent compiler source. Ten sequential repeated runs passed: 170 combined-test executions and 10,000
completion/stop cycles. Remote CI run 37415313975 did not execute any job: GitHub
reported failed recent payments or a spending-limit restriction. Linux/JDK 21
validation remains outstanding; this is not a CI test failure.

## Contract gaps this harness must not pretend are solved

### 1. Pending blocking source waits inside actors

Arbitrary pending blocking actor `await`, `readch`, `writech`, and blocking `select` still need
complete source-frame lowering onto the resumable `OresScheduler.Task` ABI.

Already-ready operations work. A genuinely pending blocking operation must never park a bounded
actor carrier.

**Required proof before calling this complete:** a source-level actor suspends on each pending
operation, another actor continues on the same bounded dispatcher, and the suspended actor later
resumes on a fresh dispatch without retaining a Java interpreter stack.

### 2. ActorMailman

This converged harness has ActorGroups, channel-backed mailboxes, CONTROL/root scheduling, and the
event bus, but it does not yet contain the separate `ActorMailman` / bounded group-outbox stack.

Do not infer Mailman correctness from ActorGroup/event-bus tests. Converge that stack first, then
prove:

- one logical serialized mailman per group;
- bounded MPSC group outbox;
- mailman executes on CONTROL rather than creating one scheduler per actor;
- mailman never scans/selects every actor mailbox;
- mailbox writes enqueue runnable actors;
- cancellation/teardown cannot strand outbox entries.

### 3. Persistent `actor` classes / full `spawn` syntax convergence

The harness proves runtime `spawnShared`, `spawnPrivate`, `spawnUntrusted`, actor-callable
syntax, ActorGroups, channels, and scheduler behavior. The final persistent
`define actor Worker as ... end` object model and full source-level `spawn` surface are still on
a separate actor-class stack and should be converged before claiming that syntax here.

### 4. Physical untrusted-isolate revocation

The independent UNTRUSTED dispatcher, policy bulkhead, quotas, and force-cancel ordering contract
are executable here. A real secondary Graal/native isolation domain must still demonstrate
physical revocation before this harness can claim hard isolate kill rather than runtime-level
bulkheading.

## Acceptance rule

A feature moves from **contract gap** to **proven** only when:

1. there is an executable test here;
2. it uses the real pinned runtime/compiler implementation, not a mock;
3. it proves scheduler/carrier ownership, not only output values;
4. cancellation and late-completion behavior are covered where applicable;
5. bounded-resource behavior is covered (mailbox/channel/outbox/backpressure);
6. the proof runs in CI on a clean checkout.
