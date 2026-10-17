#!/bin/bash
set -e

URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2"
OUT="/tmp/sherpa-onnx.tar.bz2"

echo "Downloading sherpa-onnx from $URL ..."
# curl: -L (следовать редиректам), -f (fail on HTTP error), --retry 5, --retry-delay 3, -C - (докачка)
curl -L -f --retry 5 --retry-delay 3 -C - -o "$OUT" "$URL"

echo "Extracting ..."
mkdir -p android/app/src/main/jniLibs
tar -xjf "$OUT" -C /tmp
cp -r /tmp/sherpa-onnx-v1.13.8-android/jniLibs/* android/app/src/main/jniLibs/
rm -f "$OUT"
echo "✓ sherpa-onnx готов"
