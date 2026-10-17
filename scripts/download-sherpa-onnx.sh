#!/bin/bash
set -e

URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2"
OUT="/tmp/sherpa-onnx.tar.bz2"

echo "Downloading sherpa-onnx from $URL ..."
curl -L -f --retry 5 --retry-delay 3 -C - -o "$OUT" "$URL"

echo "Extracting ..."
rm -rf /tmp/jniLibs
tar -xjf "$OUT" -C /tmp

if [ ! -d "/tmp/jniLibs" ]; then
    echo "❌ Ошибка: папка jniLibs не найдена после распаковки"
    exit 1
fi

echo "Копируем jniLibs в проект ..."
mkdir -p android/app/src/main/jniLibs
cp -r /tmp/jniLibs/* android/app/src/main/jniLibs/
rm -rf /tmp/jniLibs "$OUT"
echo "✓ sherpa-onnx готов"
