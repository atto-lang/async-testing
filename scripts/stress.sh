#!/usr/bin/env sh
# Repeat the pending-operation and fanout proofs against the bootstrapped pin.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
runs=${1:-10}
case "$runs" in *[!0-9]*|'') echo "runs must be a positive integer" >&2; exit 1 ;; esac
[ "$runs" -gt 0 ] && [ "$runs" -le 1000 ] || { echo "runs must be between 1 and 1000" >&2; exit 1; }
case "$(uname -s)" in
  Darwin) library=liboresthread.dylib ;;
  Linux) library=liboresthread.so ;;
  *) echo "native carrier proofs require Linux or macOS" >&2; exit 1 ;;
esac
[ -f ".compiler/target/native/$library" ] || { echo "run ./scripts/test.sh first" >&2; exit 1; }
expected_tree=$(cat compiler-tree.txt)
[ "$(git -C .compiler rev-parse 'HEAD^{tree}')" = "$expected_tree" ] || {
  echo "compiler cache pin differs; run ./scripts/test.sh first" >&2; exit 1;
}
round=1
while [ "$round" -le "$runs" ]; do
  echo "Concurrency repetition $round/$runs"
  mvn -B "-Dores.thread.native.path=$root/.compiler/target/native/$library" \
    -Dtest=ActorTaskChannelCombinationTest,ActorGroupChannelPipelineTest,ChannelLanguageCombinationTest,ActorStopMailboxRaceTest,SourceActorWaitProofTest,ActorGroupMailmanTest test
  round=$((round + 1))
done
