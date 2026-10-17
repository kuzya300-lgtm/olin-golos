#!/bin/bash
set -e
BASE_URL="https://huggingface.co/Alexxerm/gigaam-v3-e2e-rnnt-sherpa-onnx/resolve/main"
MODEL_DIR="android/app/src/main/assets/gigaam-v3"
mkdir -p "$MODEL_DIR"
echo "Downloading GigaAM v3 model..."
wget -q --show-progress "$BASE_URL/encoder.int8.onnx" -O "$MODEL_DIR/encoder.int8.onnx"
wget -q --show-progress "$BASE_URL/decoder.onnx" -O "$MODEL_DIR/decoder.onnx"
wget -q --show-progress "$BASE_URL/joiner.onnx" -O "$MODEL_DIR/joiner.onnx"
wget -q --show-progress "$BASE_URL/tokens.txt" -O "$MODEL_DIR/tokens.txt"
echo "✓ Модель GigaAM v3 готова"

# --- Модели для диаризации ---
DIAR_DIR="android/app/src/main/assets/diarization"
mkdir -p "$DIAR_DIR"

# 1. Модель сегментации (pyannote)
SEG_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
wget -q --show-progress "$SEG_URL" -O /tmp/seg.tar.bz2
tar -xjf /tmp/seg.tar.bz2 -C /tmp
cp /tmp/sherpa-onnx-pyannote-segmentation-3-0/model.onnx "$DIAR_DIR/segmentation.onnx"

# 2. Модель эмбеддингов (3D-Speaker)
EMB_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
wget -q --show-progress "$EMB_URL" -O "$DIAR_DIR/embedding.onnx"

echo "✓ Модели для диаризации готовы"
