#!/bin/bash
set -e

# 1. Скачиваем нативные библиотеки (jniLibs)
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2"
OUT="/tmp/sherpa-onnx.tar.bz2"

echo "Downloading sherpa-onnx native libs..."
curl -L -f --retry 5 --retry-delay 3 -C - -o "$OUT" "$URL"
rm -rf /tmp/jniLibs
tar -xjf "$OUT" -C /tmp
mkdir -p android/app/src/main/jniLibs
cp -r /tmp/jniLibs/* android/app/src/main/jniLibs/
rm -rf /tmp/jniLibs "$OUT"

# 2. Скачиваем Java/Kotlin обёртку (AAR)
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
echo "Downloading sherpa-onnx AAR wrapper..."
curl -L -f --retry 5 --retry-delay 3 -C - -o android/app/libs/sherpa-onnx.aar "$AAR_URL"

echo "✓ sherpa-onnx (jniLibs + AAR) готов"
