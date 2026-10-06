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
ignored `.compiler/` build cache. It builds the upstream JNI carrier library and
installs the compiler JAR under a commit-specific Maven version, then runs all
harness tests with that JAR and native library. CI uses the same command on Linux
and macOS with JDK 21 and 25. No compiler source is copied into this repository.

After bootstrapping, individual tests can be run with:

```sh
mvn -Dores.thread.native.path="$PWD/.compiler/target/native/liboresthread.dylib" -Dtest=SelectSchedulerProofTest test
```

Use `liboresthread.so` on Linux. The native library must come from the same
upstream checkout as the JAR.

## CI access

Both repositories are private. Configure an Actions secret named
`ORESLANG_SOURCE_TOKEN` with read-only Contents access to `oreslang-source.java`
(a fine-grained token or GitHub App installation token). The harness repository's
built-in `GITHUB_TOKEN` cannot check out another private repository. CI checks out
the pinned compiler with that token and does not persist its credentials.
Local runs use your existing Git authentication.

## Updating the compiler

Commit implementation fixes to `oreslang-source.java`, then update both
`compiler-revision.txt` and `oreslang.version` in `pom.xml` to the full upstream
commit SHA (`0.1.0-<SHA>` for the Maven version). The script checks that these pins
agree. Run `./scripts/test.sh` before submitting the update.

The current pin follows the concurrency integration stack associated with
upstream PR #269, including the fixes previously carried locally by this harness.
It also retains newer upstream isolation checks. It does not imply that this
concurrency stack has merged into upstream `main`.

For local validation of an unpushed upstream commit, set `ORESLANG_SOURCE_URL` to
an absolute path to its Git checkout. The full commit pin still applies.

See `PROOF_MATRIX.md` for the invariants covered and outstanding contract gaps.
Passing this suite does not close those gaps.
