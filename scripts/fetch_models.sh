#!/usr/bin/env bash
# Fetch large TTS models excluded from git (GitHub 100 MB per-file limit).
# Run once after cloning. Idempotent: skips if model.int8.onnx already exists.
set -euo pipefail

DEST="$(cd "$(dirname "$0")/.." && pwd)/maya/app/src/main/assets/tts/kokoro"
mkdir -p "$DEST"

URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2"
MODEL="$DEST/model.int8.onnx"

if [ -f "$MODEL" ]; then
  echo "model.int8.onnx already present — nothing to do."
  exit 0
fi

echo "Downloading Kokoro bundle (~147 MB)..."
curl -L --fail -o /tmp/kokoro.tar.bz2 "$URL"

echo "Extracting model.int8.onnx..."
tar -xjf /tmp/kokoro.tar.bz2 -C /tmp kokoro-int8-multi-lang-v1_1/model.int8.onnx
mv /tmp/kokoro-int8-multi-lang-v1_1/model.int8.onnx "$MODEL"
rm -f /tmp/kokoro.tar.bz2

echo "Done: $MODEL ($(du -h "$MODEL" | cut -f1))"
