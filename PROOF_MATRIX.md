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

The event-bus pipeline uses SHARED actors because private/isolated actors cannot
subscribe to the zero-copy shared event stream. This restriction concerns
subscription, not group membership. `ActorGroupMailmanTest` separately proves a
mixed SHARED/PRIVATE/UNTRUSTED group delivering through a CONTROL Mailman.
HungryActor has no group membership API.

Local validation on macOS arm64 / Homebrew JDK 25: 178 harness tests passed,
with the matching JNI carrier library and no failures/errors/skips. The separate
persistent actor stack passes 27 focused tests. Optimizing Graal/JVMCI execution
is not claimed. See the validation report for hosted run results.

## Newly closed runtime/lowering gaps

### Pending blocking source waits inside shared actors

`SourceActorWaitProofTest` executes genuinely pending `readch`, `writech`,
`await nb readch`, and `select`. With shared parallelism fixed at one, a peer
actor runs on that same carrier while the source waiter remains suspended. The
host completes the real rendezvous; the waiter resumes with the same actor id
and a fresh owning-scheduler dispatch, producing exactly `42`. A test-only,
allowlisted host bridge supplies the real channel and observes actor context.
The peer is a runtime actor; this is not a source-to-source actor handshake.
Cancellation/late-completion remains covered by the runtime task tests.

### ActorMailman

The compiler pin includes the compatible CONTROL-Mailman implementation.
`ActorGroupMailmanTest` checks 96 concurrent emissions through one serialized
logical Mailman, exact sequence order, CONTROL/root ownership, reply delivery
through actor mailboxes, authority rejection, bounded outbox overflow and
teardown accounting, and SHARED/PRIVATE/UNTRUSTED membership and delivery.
The overflow/late-task test deliberately pauses its dispatcher; other tests
execute the real VM CONTROL pool.

## Contract gaps this harness must not pretend are solved

### 1. Persistent `actor` classes / full `spawn` syntax convergence

The harness proves runtime `spawnShared`, `spawnPrivate`, `spawnUntrusted`, actor-callable
syntax, ActorGroups, channels, and scheduler behavior. The final persistent
`define actor Worker as ... end` object model and full source-level `spawn` surface are still on
a separate actor-class stack. A separate CI job runs that exact compiler tree,
including a source Counter spawned once and invoked repeatedly with awaited
protocol Futures (`42`, then `50`). This proves persistent state on that branch,
not convergence with this channel/select compiler pin.

### 2. Hungry ActorGroups

Standalone HungryActor tests prove dedicated JNI carriers and ordinary-dispatcher
progress. HungryActor has a separate UUID identity and cannot join ActorRuntime
ActorGroups. Dedicated hungry group membership remains an implementation gap.

### 3. Physical untrusted-isolate revocation

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
