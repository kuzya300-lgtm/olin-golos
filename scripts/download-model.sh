#!/bin/bash
set -e
BASE_URL="https://huggingface.co/Alexxerm/gigaam-v3-e2e-rnnt-sherpa-onnx/resolve/main"
MODEL_DIR="android/app/src/main/assets/gigaam-v3"
mkdir -p "$MODEL_DIR"

echo "Downloading GigaAM v3 model files..."
# Исправлены имена файлов в соответствии с репозиторием Hugging Face
curl -L -f --retry 5 --retry-delay 3 -o "$MODEL_DIR/encoder.int8.onnx" "$BASE_URL/encoder.int8.onnx"
curl -L -f --retry 5 --retry-delay 3 -o "$MODEL_DIR/decoder.onnx"       "$BASE_URL/decoder.onnx"
curl -L -f --retry 5 --retry-delay 3 -o "$MODEL_DIR/joiner.onnx"        "$BASE_URL/joiner.onnx"
curl -L -f --retry 5 --retry-delay 3 -o "$MODEL_DIR/tokens.txt"         "$BASE_URL/tokens.txt"

# --- Модели для диаризации ---
DIAR_DIR="android/app/src/main/assets/diarization"
mkdir -p "$DIAR_DIR"

# 1. Модель сегментации (pyannote)
SEG_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
curl -L -f --retry 5 --retry-delay 3 -o /tmp/seg.tar.bz2 "$SEG_URL"
tar -xjf /tmp/seg.tar.bz2 -C /tmp
cp /tmp/sherpa-onnx-pyannote-segmentation-3-0/model.onnx "$DIAR_DIR/segmentation.onnx"

# 2. Модель эмбеддингов (3D-Speaker)
EMB_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
curl -L -f --retry 5 --retry-delay 3 -o "$DIAR_DIR/embedding.onnx" "$EMB_URL"

echo "✓ Все модели готовы"
