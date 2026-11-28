#!/usr/bin/env sh
# Build the real upstream compiler at an immutable revision, then test its JAR.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
revision=$(cat compiler-revision.txt)
case "$revision" in
  *[!0-9a-f]*|'') echo "invalid compiler revision" >&2; exit 1 ;;
esac
[ "${#revision}" -eq 40 ] || { echo "compiler revision must be a full SHA" >&2; exit 1; }
version="0.1.0-$revision"
grep -Fq "<oreslang.version>$version</oreslang.version>" pom.xml || {
  echo "pom.xml and compiler-revision.txt disagree" >&2; exit 1;
}
source_url=${ORESLANG_SOURCE_URL:-https://github.com/atto-lang/atto-source.java.git}
# Preserve developer caches before any checkout/fetch can switch their HEAD.
if [ -L .compiler ]; then
  echo "compiler cache must not be a symlink" >&2; exit 1
fi
if [ -d .compiler/.git ] && [ -n "$(git -C .compiler status --porcelain --untracked-files=all)" ]; then
  echo "compiler checkout is dirty; preserving HEAD and local changes" >&2; exit 1
fi
# .compiler is a disposable build cache owned by this script.
if [ ! -d .compiler/.git ]; then
  if [ -e .compiler ]; then
    echo ".compiler exists but is not a Git checkout" >&2; exit 1
  fi
  git init -q .compiler
fi
expected_tree=$(cat compiler-tree.txt)
case "$expected_tree" in
  *[!0-9a-f]*|'') echo "invalid compiler tree" >&2; exit 1 ;;
esac
[ "${#expected_tree}" -eq 40 ] || { echo "compiler tree must be a full SHA" >&2; exit 1; }
# CI may provide a same-repository mirror commit with the identical source tree.
# Its commit identity differs, but the implementation must match byte-for-byte.
if [ "$(git -C .compiler rev-parse 'HEAD^{tree}' 2>/dev/null || true)" = "$expected_tree" ]; then
  git -C .compiler checkout --detach HEAD
else
  if ! git -C .compiler cat-file -e "$revision^{commit}" 2>/dev/null; then
    git -C .compiler fetch --depth 1 "$source_url" "$revision"
  fi
  git -C .compiler checkout --detach "$revision"
fi
[ "$(git -C .compiler rev-parse 'HEAD^{tree}')" = "$expected_tree" ] || {
  echo "compiler source tree does not match compiler-tree.txt" >&2; exit 1;
}

# Reject dirty source instead of discarding a developer's changes. Build from
# an archive of tracked files, so ignored cache files cannot enter compilation.
if [ -n "$(git -C .compiler status --porcelain --untracked-files=all)" ]; then
  echo "compiler checkout is dirty; preserve changes before building" >&2
  exit 1
fi
mkdir -p .work
build_dir=$(mktemp -d "$root/.work/compiler-build.XXXXXXXX")
git -C .compiler archive --format=tar --output="$build_dir/source.tar" HEAD
tar -xf "$build_dir/source.tar" -C "$build_dir"

# Version the installed dependency by commit, avoiding mutable SNAPSHOT collisions.
(cd "$build_dir" && mvn -B org.codehaus.mojo:versions-maven-plugin:2.18.0:set \
  -DnewVersion="$version" -DgenerateBackupPoms=false && mvn -B -DskipTests clean install)
case "$(uname -s)" in
  Darwin) library=liboresthread.dylib ;;
  Linux) library=liboresthread.so ;;
  *) echo "native carrier tests require Linux or macOS" >&2; exit 1 ;;
esac
mkdir -p .compiler/target/native
cp "$build_dir/target/native/$library" ".compiler/target/native/$library"
mvn -B "-Dores.thread.native.path=$root/.compiler/target/native/$library" \
  -DtrimStackTrace=false clean test "$@"
