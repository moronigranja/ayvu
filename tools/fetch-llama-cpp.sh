#!/usr/bin/env bash
# Fetch the pinned llama.cpp source into build/llama.cpp-src (gitignored).
#
# core-llm's CMake `add_subdirectory`s this tree; the commit below is the
# revision the translate model was MEASURED against on the S22 (decisions
# #161/#162 — llama.cpp-android, armv8.6 i8mm kernel variant, 22.3 tok/s).
# A different revision invalidates the measured claim: re-run the FLORES gate
# (docs/prints/beam-spike/lfm12_gate.py) before bumping.
#
# Shallow fetch of the exact commit (~60 MB, no full history).
#
# Usage:  tools/fetch-llama-cpp.sh
set -euo pipefail
cd "$(dirname "$0")/.."

PIN="b75ecd1971bf2d3f29d5d334520868a01942cbc6"
SRC="$(pwd)/build/llama.cpp-src"

if [ ! -d "$SRC/.git" ]; then
  mkdir -p "$SRC"
  git -C "$SRC" init -q
  git -C "$SRC" remote add origin https://github.com/ggml-org/llama.cpp
fi
git -C "$SRC" fetch -q --depth 1 origin "$PIN"
git -C "$SRC" checkout -q FETCH_HEAD

HEAD="$(git -C "$SRC" rev-parse HEAD)"
if [ "$HEAD" != "$PIN" ]; then
  echo "llama.cpp source is at $HEAD, expected $PIN" >&2
  exit 1
fi
echo "llama.cpp source at $HEAD ($(du -sh "$SRC" | cut -f1)) in $SRC"
