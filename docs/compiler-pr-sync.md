# Compiler PR compatibility checkpoint — 2026-10-06

**Component:** Scheduler, actors, channel/Future/select formal and integration tests.

Compiler source PRs **all open** at review: [#398](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/398), [#399](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/399), [#404](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/404), [#405](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/405), [#406](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/406), [#408](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/408), [#409](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/409), [#410](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/410), [#411](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/411), [#412](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/412).

## Integration dependency constraints

- `do select` stack: #405 -> #406 -> #408 -> #412; do not compile downstream tests against only one stack head.
- `do match` #404 is itself on a match feature branch; #398 is a separate map/exhaustiveness proposal.
- Actor isolation #399, SelectPlan #409, task-safe channels #410 and orthogonal binding qualifiers #411 are separate feature changes.
- Keep all current compiler pins, public exports, baseline files and default test behavior stable until exact source SHAs pass.

## async-testing-specific tests and hardening

- Add branch-gated positive+negative cases for do-select return-value evaluation/discard, local return scope, caller type preservation (#405/#406/#408/#412).
- Validate do nb select actor-only, no escaping break/continue, capture/ownership safety; exactly one ready case commits (#412).
- Exercise local data-only Channel<T> at task boundary while refusing mailbox channel/function payload transport (#410/#411).
- Test optional SelectPlan repeated/dynamic arbitration against unchanged SelectSet under fair, first, close, cancel and send/receive races (#409).
- For rt proxy, prove single-writer turn leases and cooperative continuation safety, no shared mutable writes (#399).
- Mirror exact Git tree into a funded Actions org if no runner; verify tree/blob equality and only claim actual executed test success.

## Verification gate

- Expected baseline check: `mvn -q test (with exact compiler head SHA) and existing concurrency-proof workflow`. This PR does **not** claim that check has been run.
- Add exact-head branch-specific integration tests before feature rollout. Negative tests must reject callable-bearing mailbox payloads, unsafe actor aliases, and discarded-return scope leaks.
- `const` is fixed readonly; `val` is fixed mutable (`const mut`); `let` is rebindable readonly; `let mut` is rebindable mutable, subject to borrow checking.
- Public Oreslang should use `rt copy`, `rt borrow`, `rt take`, `rt share` (and checked `rt proxy`) rather than `&`/`*` pointer syntax.
- If CI has no runner (runner ID zero, steps empty), no result was executed. Mirror the exact source tree to an organization with available GitHub Actions minutes, validate tree/blob hashes, and retain proof of executed jobs. Do not propagate secrets to the test org.

This checklist documents required follow-up **without pretending unmerged compiler semantics are available on main**.
