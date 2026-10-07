# Binding/reference and actor-mailbox proof matrix

**Acceptance contract, not a statement of support in the current compiler pin.** This document may land independently of the compiler implementation. Every case below requires compiler-backed evidence before a pin or API is promoted.

Compiler branch: [oreslang-source.java #411](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/411). This branch is a coordination/acceptance plan; do not claim test pass until the test harness is pinned to the exact validated compiler tree.

### Language conformance

1. `const obj`: reject assignment and all direct/nested method/field/index mutations.
2. `val obj` = `const mut obj`: permit referent mutation if exclusive/owned; reject rebinding.
3. `let obj`: permit rebinding; reject referent mutation.
4. `let mut obj`: permit rebinding and mutation if owned.
5. A valid runtime-created struct/class initializer may use `const`; values still obey the declared field types. Canonical `infer struct {foo: "bar"}` has a closed compile-time shape and permanent readonly capability. Reject `val`, `const mut`, `let mut`, mutable aliases/borrows, and nested mutation of inferred structs. Cover readonly propagation through annotation, return, tuple/Option wrapping, and async suspension; never reintroduce `obj{}` or `DynamicStruct`.
6. Use canonical `when` arms in select; `case` is a compiler compatibility spelling that the formatter normalizes only at select arm boundaries. Test `case` and `when` as ordinary identifiers outside those boundaries. Test read-only, fixed mutable, and rebindable arm bindings against the exact compiler grammar; losing select arms never transfer ownership.

### Ownership and channel cases

7. Reject mutate-while-shared-borrowed, dual exclusive borrows, use-after-move, aliased writes, mutable closure captures through readonly views, and borrow across illegal await/actor-turn boundaries.
8. Permit local channel transport of callable/object references *only after* capture/lifetime/domain checks are implemented; current compiler deliberately rejects function-bearing channel types.
9. Actor mailbox rejects functions and borrowed/raw pointers **transitively**, including nested Option, List, Tuple, structs and every reachable declared field; serialize owned values without preserving mutable aliases across actors.
10. Scheduler suspends a blocking select without holding a native carrier; bounded nonblocking selections; no busy-spin/future leaks on `do nb select`.

### CI proof

Validate compiler HEAD SHA and full Git tree identity. If source repo Actions runs end without steps, mirror exact private source tree into a funded **private** test repository via GitHub API, no extra secrets. Only accept green tests against identical source blob content and recorded tree SHA. When upstream compiler becomes validated, change `compiler-revision.txt`, `pom.xml`, and `compiler-tree.txt` together.
