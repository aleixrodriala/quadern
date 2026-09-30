#!/usr/bin/env bash
# Re-vendors whisper.cpp into src/main/cpp/whisper.cpp from a GitHub release tarball, keeping only
# what the Android build needs (ggml core + CPU backend, whisper sources, CMake files, LICENSE).
#
#   scripts/vendor-whisper-cpp.sh v1.9.4
#
# After running it, update the tag/commit/sha256 in README.md and rebuild.
set -euo pipefail

TAG="${1:?usage: $0 <whisper.cpp tag, e.g. v1.9.4>}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$HERE/src/main/cpp/whisper.cpp"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl -fsSL -o "$TMP/src.tar.gz" "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/${TAG}.tar.gz"
echo "sha256: $(sha256sum "$TMP/src.tar.gz" | cut -d' ' -f1)"
# Annotated tags list the tag object first and the peeled commit as "<tag>^{}"; prefer the commit.
REFS="$(git ls-remote https://github.com/ggml-org/whisper.cpp "refs/tags/${TAG}" "refs/tags/${TAG}^{}")"
COMMIT="$( (grep '\^{}$' <<<"$REFS" || echo "$REFS") | head -1 | cut -f1)"
echo "commit: $COMMIT"
tar -xzf "$TMP/src.tar.gz" -C "$TMP"
SRC="$(find "$TMP" -mindepth 1 -maxdepth 1 -type d -name 'whisper.cpp-*')"

rm -rf "$DEST"
mkdir -p "$DEST/src"
cp "$SRC/LICENSE" "$DEST/"
cp -r "$SRC/include" "$DEST/"
cp "$SRC/src/whisper.cpp" "$SRC/src/whisper-arch.h" "$DEST/src/"
# ggml: CMake files, public headers and the core + CPU backend sources.
mkdir -p "$DEST/ggml"
cp "$SRC/ggml/CMakeLists.txt" "$DEST/ggml/"
cp -r "$SRC/ggml/cmake" "$SRC/ggml/include" "$SRC/ggml/src" "$DEST/ggml/"
# Drop every non-CPU backend (each is only add_subdirectory()'d when its GGML_<NAME> option is ON)
# and the CPU code paths for architectures Android does not ship.
find "$DEST/ggml/src" -mindepth 1 -maxdepth 1 -type d ! -name ggml-cpu -exec rm -rf {} +
rm -rf "$DEST/ggml/src/ggml-cpu/spacemit" "$DEST/ggml/src/ggml-cpu/kleidiai"
find "$DEST/ggml/src/ggml-cpu/arch" -mindepth 1 -maxdepth 1 -type d ! -name arm ! -name x86 -exec rm -rf {} +
# Headers for backends we do not build are harmless and tiny; keep ggml/include intact.
# VERSION is read by src/main/cpp/CMakeLists.txt (whisper_version()) and documents provenance.
printf '%s\n%s\n' "${TAG#v}" "$COMMIT" > "$DEST/VERSION"
du -sh "$DEST"
