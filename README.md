# Oreslang Async / Actor / Channel Proof Harness

This repository tests the real Oreslang compiler/runtime as a Maven dependency.
It contains concurrency proofs only; compiler, runtime, and native carrier sources
live in [oreslang-source.java](https://github.com/ores-truffle-oreslang/oreslang-source.java).

## Run

Install JDK 21 or 25, Maven, Git, and a C compiler on Linux or macOS, then run:

```sh
./scripts/test.sh
```

The script fetches the exact upstream commit in `compiler-revision.txt` into the
ignored `.compiler/` build cache. It rejects dirty source, exports tracked files into a fresh ignored `.work/` build directory, builds the upstream JNI carrier library and
installs the compiler JAR under a commit-specific Maven version, then runs all
harness tests with that JAR and native library. CI uses the same command on Linux
and macOS with JDK 21 and 25. No compiler source is copied into this repository.

After bootstrapping, individual tests can be run with:

```sh
mvn -Dores.thread.native.path="$PWD/.compiler/target/native/liboresthread.dylib" -Dtest=SelectSchedulerProofTest test
```

Use `liboresthread.so` on Linux. The native library must come from the same
upstream checkout as the JAR.

## CI source mirror

CI checks out `compiler-mirror/<upstream-SHA>` from this same repository using
its ordinary `GITHUB_TOKEN`. That branch contains an immutable source-only
snapshot, outside the harness's main branch. `compiler-tree.txt` records the
upstream Git tree; the script rejects a mirror unless that tree matches exactly.
No cross-repository secret or token is required. Local runs can still fetch the
original compiler using existing Git authentication.

The alternate-org validation carrier uses the same mirror technique so it can
build with its own Actions allocation. Mirror branches are test transport, not a
second implementation: fixes remain upstream and mirrors are replaced by pin.

## Updating the compiler

Commit implementation fixes to `oreslang-source.java`, then update `compiler-revision.txt`, `compiler-tree.txt`, and `oreslang.version` in `pom.xml` to the full upstream
commit SHA (`0.1.0-<SHA>` for the Maven version). The script checks that these pins
agree. Publish the matching `compiler-mirror/<SHA>` source tree through the
GitHub connector, then run `./scripts/test.sh` before submitting the update.

The current pin follows compiler convergence #431, actor proofs #415, and the
shared-carrier/host-handle fixes in #432.
It also retains newer upstream isolation checks. It does not imply that this
concurrency stack has merged into upstream `main`.

For local validation of an unpushed upstream commit, set `ORESLANG_SOURCE_URL` to
an absolute path to its Git checkout. The full commit pin still applies.

See `PROOF_MATRIX.md` for the invariants covered and outstanding contract gaps.
Passing this suite does not close those gaps.

## Combined proofs and repeated runs

`ChannelLanguageCombinationTest` executes six real source programs, including
async/await + rendezvous read/write Futures, dynamic select, buffered blocking
operations, shared/isoactor callable results, `nb cb writech`, and detached
`nb select` arms. The compiler pin includes a fix for suspension detection in
nested call arguments/receivers: `stdio.stdout.write(worker())` must print the
actor result rather than an internal Future object.

`ActorTaskChannelCombinationTest` exercises eight pending operation/domain
combinations (read, write, select-read, select-write × SHARED/PRIVATE) and actor
cancellation. `ActorGroupChannelPipelineTest` connects reliable event fanout,
eight shared actor tasks on one carrier, channel writes, and scheduler select.
These tests distinguish the resumable runtime ABI from source lowering.

After `./scripts/test.sh`, run `./scripts/stress.sh 10` to repeat all four
classes in fresh Maven test processes. CI runs these repetitions in every
Linux/macOS × JDK 21/25 job. See the proof matrix for the remaining gaps.

`ActorStopMailboxRaceTest` exercises 1,000 task-completion/normal-stop races per
run. The compiler pin also fixes mailbox polling racing with channel close.

## Remaining-gap validation (2026-10-06)

`SourceActorWaitProofTest` proves genuinely pending source `readch`, `writech`,
`await nb readch`, and `select` on a single shared carrier: another actor executes
before the host releases the rendezvous, and the source actor resumes with the
same identity on a fresh scheduler dispatch. Its allowlisted test bridge supplies
a real channel; it does not simulate the runtime or scheduler.

`ActorGroupMailmanTest` proves serialized CONTROL delivery, sequencing, authority
boundaries, bounded outbox cleanup, and a mixed SHARED/PRIVATE/UNTRUSTED group.
The converged harness has 199 tests, including the seven binding/mailbox acceptance groups.

The persistent-actor CI job now uses the same compiler pin and tree as the
concurrency harness. It runs lexical, spawn, mail-type, shared-code-image and
actor-state ownership proofs on that converged implementation.

HungryActor remains a standalone dedicated-native-carrier API. Its existing
carrier and starvation tests pass, but it cannot join ActorRuntime ActorGroups.
Hungry group membership and unified persistent-class/channel lowering therefore
remain implementation gaps, not passing coverage.

## 2026-10-06 compiler PR compatibility

For the latest 10 upstream Oreslang compiler PRs, see [docs/compiler-pr-sync.md](docs/compiler-pr-sync.md). The compiler convergence is merged and the pins now identify its validated successor. The proposal document is historical; current executable coverage is listed in BINDING-AND-MAILBOX-PROOF-MATRIX.md.

## Repository ownership and migration (2026-11-08)

**Public name:** AttoLang (formerly Oreslang). This remains `ores-truffle-oreslang/async-testing` until a real authorized repo transfer. Preserve established `.ores` inputs and old tool/package names; do not claim feature parity from a branding or README change.

- **Scope:** structured-concurrency, cancellation, task/actor/channel/select test fixtures and independent receipts.
- **Not this repo:** actual backend scheduler implementations or source-free guest-test claims.
- **Where code belongs:** [atto-lang](https://github.com/atto-lang) is the canonical home for specs, compilers, portable core/std, developer tooling, conformance and ecosystem examples. [atto-co](https://github.com/atto-co) separately governs deployment, identity, billing, tenant hosting and other managed infrastructure. Existing `atto-*` repos are not automatically transferred source identities.
- **Planned first-stage destination (not approved/executed):** `atto-lang/async-testing`, under the same name and GitHub numeric repository ID. Direct legacy-org → `atto-co` transfers are not authorized in this wave; future product carve-outs require separate review.

**Migration checklist:** announce precise repo/head/rollback owner in [coordination issue #26](https://github.com/ores-truffle-oreslang/.github/issues/26); use private [migration plan #25](https://github.com/ores-truffle-oreslang/.github/pull/25). Obtain source/target admins and independent security signoff, verify target availability/protections/access, capture numeric repo ID and exact head SHA/tree, PRs/releases, Actions/runners/secrets, packages, deploy keys, GitHub Apps, webhooks, Pages and consumers. Never clone private source into a new org to bypass approval. Confirm stable identity/history/tree after authorized GitHub transfer and rerun exact-head CI at destination. **Zero-step jobs (`steps=[]` or `null`) are `NOT_EXECUTED`; host-only or source-free probes are not compiler-backed AttoLang guest receipts.** Move package/import references and names only after validated cutover.
