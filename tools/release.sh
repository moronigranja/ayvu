#!/usr/bin/env bash
# Build and publish the SIGNED release APK (decisions #126).
#
# Signing is deliberately manual and local: the release keystore lives OUTSIDE
# the repo (~/.android/ayvu-release.jks via keystore.properties, gitignored);
# CI only gate-assembles the release build UNSIGNED on tags. Never publish the
# unsigned app-release-unsigned.apk.
#
# The SHIPPED artifact is named after the product and the version
# (`Ayvu-<versionName>.apk`, decisions #176) so a downloaded file says what it
# is. AGP's public variant API no longer exposes the output file name and the
# legacy `applicationVariants` DSL is deprecated, so the rename happens here, at
# the shipping step, and the digest/signature checks run on the shipped copy.
#
# Usage:
#   tools/release.sh                  # build + verify the signed APK
#   tools/release.sh --upload         # build + create a DRAFT GitHub release v$VERSION
#   tools/release.sh --upload --publish   # ... and make it live
#   tools/release.sh --notes FILE     # release notes file (implies --upload)
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f keystore.properties ]; then
  echo "keystore.properties missing — create it with storeFile/storePassword/keyAlias/keyPassword" >&2
  echo "(points at the release keystore OUTSIDE the repo). Without it assembleRelease is UNSIGNED." >&2
  exit 1
fi

UPLOAD=0; PUBLISH=0; NOTES_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --upload) UPLOAD=1 ;;
    --publish) PUBLISH=1 ;;
    --notes) NOTES_FILE="${2:-}"; shift ;;
    *) echo "unknown arg: $1 (expected --upload, --publish, --notes FILE)" >&2; exit 1 ;;
  esac
  shift
done

VERSION=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
APK=app/build/outputs/apk/release/app-release.apk
SHIP=build/release/Ayvu-$VERSION.apk

./gradlew :app:assembleRelease
[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 1; }

# The shipped name is a copy of the signed output, byte-identical (the filename
# is not inside the APK), so the digest below is the digest of what is uploaded.
mkdir -p "$(dirname "$SHIP")"
cp -f "$APK" "$SHIP"

SDK_DIR=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null || true)
APKSIGNER=$(ls "$SDK_DIR"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)

echo "== $SHIP ($(du -h "$SHIP" | cut -f1))"
sha256sum "$SHIP"
if [ -n "$APKSIGNER" ]; then
  "$APKSIGNER" verify --print-certs "$SHIP" | head -8
else
  keytool -printcert -jarfile "$SHIP" 2>/dev/null | sed -n '1,3p'
fi

if [ "$UPLOAD" -eq 1 ]; then
  command -v gh >/dev/null || { echo "--upload needs the gh CLI" >&2; exit 1; }
  NOTES_ARGS=()
  if [ -n "$NOTES_FILE" ]; then
    [ -f "$NOTES_FILE" ] || { echo "notes file not found: $NOTES_FILE" >&2; exit 1; }
    NOTES_ARGS=(--notes-file "$NOTES_FILE")
  else
    NOTES_ARGS=(--generate-notes)
  fi
  DRAFT_ARGS=()
  [ "$PUBLISH" -eq 1 ] || DRAFT_ARGS=(--draft)
  gh release create "v$VERSION" "$SHIP" "${NOTES_ARGS[@]}" "${DRAFT_ARGS[@]}" --title "Ayvu v$VERSION"
  echo "Release v$VERSION $([ "$PUBLISH" -eq 1 ] && echo published || echo drafted)."
fi