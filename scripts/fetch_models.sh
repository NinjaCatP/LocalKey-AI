#!/bin/bash
# Downloads the two on-device models into models/ (not stored in git).
set -e
cd "$(dirname "$0")/.."; mkdir -p models
dl() { [ -s "models/$2" ] || curl -L --fail -o "models/$2" "$1"; }
# Qwen2.5-0.5B-Instruct, GGUF Q4_K_M (Apache-2.0)
dl https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf qwen2.5-0.5b-instruct-q4_k_m.gguf
# Whisper base.en, ggml q5_1 (MIT)
dl https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin ggml-base.en-q5_1.bin
ls -l models
