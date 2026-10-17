#!/bin/bash
set -e
# ВАЖНО: правильное имя файла содержит 'v' перед версией!
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2"
echo "Downloading sherpa-onnx from $URL ..."
wget -q --show-progress "$URL" -O /tmp/sherpa-onnx.tar.bz2
mkdir -p android/app/src/main/jniLibs
tar -xjf /tmp/sherpa-onnx.tar.bz2 -C /tmp
# Копируем нативные библиотеки для всех архитектур
cp -r /tmp/sherpa-onnx-v1.13.8-android/jniLibs/* android/app/src/main/jniLibs/
echo "✓ sherpa-onnx готов"
