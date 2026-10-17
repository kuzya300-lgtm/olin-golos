#!/bin/bash
set -e
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5-android.tar.bz2"
echo "Downloading sherpa-onnx from $URL ..."
wget -q --show-progress "$URL" -O /tmp/sherpa-onnx.tar.bz2
mkdir -p android/app/src/main/jniLibs
tar -xjf /tmp/sherpa-onnx.tar.bz2 -C /tmp
cp -r /tmp/sherpa-onnx-1.13.5/android/* android/app/src/main/jniLibs/
echo "✓ sherpa-onnx готов"
