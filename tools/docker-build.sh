#!/usr/bin/env bash
# Run a Gradle task inside the Android toolchain image.
#
# The SDK/NDK are baked into the image; Gradle/Maven caches live in named volumes, so
# rebuilds are fast and the workspace stays source-only. Built APKs land in the
# workspace (host-owned — fine for adb install).
#
# Runs as the invoking host user (uid/gid passed through via --user), so nothing the
# build writes into the mounted workspace or cache volumes is root-owned — host
# `./gradlew` runs are never blocked by stale root-owned files.
#
# Usage:
#   tools/docker-build.sh :core-locate:test     # JVM-only, fast
#   tools/docker-build.sh assembleDebug         # full APK
#   tools/docker-build.sh installDebug          # build + adb install to connected device
set -euo pipefail

IMAGE="${IMAGE:-ayvu-android}"
UID_NUM="$(id -u)"
GID_NUM="$(id -g)"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "Image '$IMAGE' not found. Build it first:  docker build -t $IMAGE ." >&2
  exit 1
fi

# The cache volumes may still be root-owned from an earlier toolchain run; the
# invoking uid must own them. Detect that and print the one-time migration instead of
# failing cryptically in Gradle.
#
# Fresh volumes need no setup: this check mounts at the same paths as the build below,
# so a brand-new volume is initialized from the image's sticky 1777 /builder/.gradle
# and /builder/.local and is writable by any uid. Only legacy root-owned volumes (from
# a toolchain predating that Dockerfile behavior) fail the check and trigger the
# migration command below.
if ! docker run --rm -u "$UID_NUM:$GID_NUM" \
      -v android-gradle:/builder/.gradle -v android-local:/builder/.local \
      "$IMAGE" sh -c 'test -w /builder/.gradle && test -w /builder/.local'; then
  echo "Gradle cache volumes are not writable by uid $UID_NUM (root-owned from an earlier toolchain)." >&2
  echo "Migrate once with:" >&2
  echo "  docker run --rm -u 0 -v android-gradle:/builder/.gradle -v android-local:/builder/.local \\" >&2
  echo "      $IMAGE chown -R $UID_NUM:$GID_NUM /builder/.gradle /builder/.local" >&2
  exit 1
fi

exec docker run --rm \
  -u "$UID_NUM:$GID_NUM" \
  -e HOME=/builder \
  -e GRADLE_USER_HOME=/builder/.gradle \
  -e ANDROID_USER_HOME=/builder/.android \
  -w /workspace \
  -v "$PWD":/workspace \
  -v android-gradle:/builder/.gradle \
  -v android-local:/builder/.local \
  "$IMAGE" ./gradlew "$@"
