# Binding/reference and actor-mailbox proof matrix

Compiler branch: [oreslang-source.java #411](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/411). This branch is a coordination/acceptance plan; do not claim test pass until the test harness is pinned to the exact validated compiler tree.

### Language conformance

1. `const obj`: reject assignment and all direct/nested method/field/index mutations.
2. `val obj` = `const mut obj`: permit referent mutation if exclusive/owned; reject rebinding.
3. `let obj`: permit rebinding; reject referent mutation.
4. `let mut obj`: permit rebinding and mutation if owned.
5. A `const` runtime-created struct/class initializer must typecheck; `struct infer` is pending syntax work and must infer static shape, not a dynamic map.
6. A `case msg = readch(ch) -> {}` bind is read-only; `case val msg` has mutable referent; `case mut msg` additionally allows rebinding; losing select arms never transfer ownership.

### Ownership and channel cases

7. Reject mutate-while-shared-borrowed, dual exclusive borrows, use-after-move, aliased writes, mutable closure captures through readonly views, and borrow across illegal await/actor-turn boundaries.
8. Permit local channel transport of callable/object references *only after* capture/lifetime/domain checks are implemented; current compiler deliberately rejects function-bearing channel types.
9. Actor mailbox rejects functions and borrowed/raw pointers **transitively**, including nested Option, List, Tuple, structs and unknown extension fields; serialize owned values without preserving mutable aliases across actors.
10. Scheduler suspends a blocking select without holding a native carrier; bounded nonblocking selections; no busy-spin/future leaks on `do nb select`.

### CI proof

Validate compiler HEAD SHA and full Git tree identity. If source repo Actions runs end without steps, mirror exact private source tree into a funded **private** test repository via GitHub API, no extra secrets. Only accept green tests against identical source blob content and recorded tree SHA. When upstream compiler becomes validated, change both `compiler-revision.txt` and `pom.xml` + `compiler-tree.txt` together.
