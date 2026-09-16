#!/usr/bin/env bash
# Cross-compile espeak-ng for arm64-v8a with the toolchain image's NDK.
#
# Spike B outcome codified (decisions #32): the espeak-ng Android bundle for
# the raw Kokoro port. Builds libespeak-ng.so at a pinned release tag and
# pairs it with the matching compiled espeak-ng-data — data generation cannot
# run in a cross build (the generator is the arm64 binary itself), so the
# data comes from the host's espeak-ng installation (version must match the
# tag; default path is the standard Ubuntu location).
#
# Host-owned outputs under build/ (gitignored):
#   espeak-ng-152/lib/arm64-v8a/libespeak-ng.so   ~2.1 MB
#   espeak-ng-152/espeak-ng-data/                 19 MB, arch-independent
#
# Usage:  tools/build-espeak-android.sh
# Requires: docker, the ayvu-android image, and espeak-ng on the host.

set -euo pipefail

IMAGE="${IMAGE:-ayvu-android}"
NDK="27.2.12479018"                 # must match the toolchain image (Dockerfile)
ABI="arm64-v8a"
ESPEAK_TAG="${ESPEAK_TAG:-1.52.0}"  # the tag the #28 phonemizer oracle is frozen against
DATA_DIR="${ESPEAK_DATA_DIR:-/usr/share/espeak-ng-data}"
ROOT="$(pwd)/build"
SRC="$ROOT/espeak-ng-src"
OUT="$ROOT/espeak-ng-152"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "Image '$IMAGE' not found. Build it first:  docker build -t $IMAGE ." >&2
  exit 1
fi
if [ ! -d "$DATA_DIR" ]; then
  echo "espeak-ng data not found at $DATA_DIR (set ESPEAK_DATA_DIR)." >&2
  exit 1
fi

mkdir -p "$ROOT"
if [ ! -d "$SRC/.git" ]; then
  echo "cloning espeak-ng (shallow) into $SRC"
  git clone --depth 1 https://github.com/espeak-ng/espeak-ng.git "$SRC"
fi
git -C "$SRC" fetch --tags --depth 1 origin
git -C "$SRC" checkout -q "$ESPEAK_TAG"
echo "espeak-ng source at: $(git -C "$SRC" describe --tags --always)"

# Runs as root (ephemeral container): apt is needed for cmake/ninja/git (the
# SDK ships none; git is required by 1.52.0's FetchContent of `sonic`).
# Only the library target is built — the espeak-ng-data target executes the
# cross-compiled generator binary on the host and must be skipped.
docker run --rm -u 0 \
  -v "$SRC":/src -w /src \
  "$IMAGE" bash -c '
    set -euo pipefail
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq --no-install-recommends cmake ninja-build git >/dev/null
    cmake -S /src -B /src/build-152 -G Ninja -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE=/opt/android-sdk/ndk/'"$NDK"'/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI='"$ABI"' -DANDROID_PLATFORM=android-26 \
        -DBUILD_SHARED_LIBS=ON -DBUILD_TESTS=OFF -DUSE_ASYNC=OFF
    cmake --build /src/build-152 --target espeak-ng -j"$(nproc)"
  '

mkdir -p "$OUT/lib/$ABI"
cp "$SRC/build-152/src/libespeak-ng/libespeak-ng.so" "$OUT/lib/$ABI/"
rm -rf "$OUT/espeak-ng-data"
cp -r "$DATA_DIR" "$OUT/espeak-ng-data"

echo "---"
echo "lib:  $OUT/lib/$ABI/libespeak-ng.so  ($(du -h "$OUT/lib/$ABI/libespeak-ng.so" | cut -f1))"
echo "data: $OUT/espeak-ng-data            ($(du -sh "$OUT/espeak-ng-data" | cut -f1))"
echo "lib sha256: $(sha256sum "$OUT/lib/$ABI/libespeak-ng.so" | cut -d' ' -f1)"
echo "NOTE: data was copied from $DATA_DIR — keep it byte-identical to the tag build's data (1.52.0)."
