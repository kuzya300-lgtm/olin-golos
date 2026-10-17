#!/bin/bash
set -e

URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2"
OUT="/tmp/sherpa-onnx.tar.bz2"

echo "Downloading sherpa-onnx from $URL ..."
curl -L -f --retry 5 --retry-delay 3 -C - -o "$OUT" "$URL"

echo "Extracting ..."
# Очищаем старые файлы, если они есть
rm -rf /tmp/sherpa-onnx-*-android 2>/dev/null || true
tar -xjf "$OUT" -C /tmp

# Ищем папку, которая распаковалась (на случай если имя изменится)
EXTRACTED_DIR=$(find /tmp -maxdepth 1 -type d -name "sherpa-onnx-*android*" | head -n 1)

if [ -z "$EXTRACTED_DIR" ]; then
    echo "❌ Ошибка: не удалось найти распакованную папку sherpa-onnx"
    exit 1
fi

echo "Копируем из $EXTRACTED_DIR ..."
mkdir -p android/app/src/main/jniLibs
# Копируем папки arm64-v8a, armeabi-v7a и другие напрямую в jniLibs
cp -r "$EXTRACTED_DIR"/* android/app/src/main/jniLibs/

rm -f "$OUT"
echo "✓ sherpa-onnx готов"
