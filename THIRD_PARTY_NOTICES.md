# Third-party notices

LocalKey AI's own code is MIT (see LICENSE). The app and this repo use or bundle:

| Component | Use | License | Source |
|---|---|---|---|
| llama.cpp / ggml (commit cd0a5b6a) | Native LLM runtime in `libaikeyboard.so` (git submodule) | MIT, Copyright (c) 2023-2026 The ggml authors | https://github.com/ggml-org/llama.cpp |
| whisper.cpp / ggml (commit 0c7be696) | Native speech runtime in `libaikvoice.so` (git submodule) | MIT, Copyright (c) 2023-2026 The ggml authors | https://github.com/ggml-org/whisper.cpp |
| Qwen2.5-0.5B-Instruct, GGUF Q4_K_M | Rewrite / next-word model, bundled in APK as `assets/model.gguf` (not in git) | Apache License 2.0, Copyright Alibaba Cloud (Qwen team) | https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF |
| OpenAI Whisper base.en weights (ggml q5_1 conversion) | Voice typing model, bundled in APK as `assets/whisper.bin` (not in git) | MIT, Copyright (c) 2022 OpenAI | https://github.com/openai/whisper , https://huggingface.co/ggerganov/whisper.cpp |
| FrequencyWords English list (Hermit Dave), filtered | `app/assets/words.txt`, `data/en_50k.txt` | CC BY-SA 4.0 (these files remain under CC BY-SA 4.0; changes: filtered/truncated) | https://github.com/hermitdave/FrequencyWords |
| Unicode emoji-test.txt | `data/emoji-test.txt`, derived `app/assets/emoji.txt` | Unicode License v3, Copyright (c) Unicode, Inc. | https://unicode.org/Public/emoji/latest/ |

Emoji glyphs are drawn with the phone's system font; none are bundled.

Test-only tooling (not bundled in the app or repo): JUnit (EPL-1.0), Robolectric (MIT), Piper TTS / espeak-ng used to generate test audio.

The full MIT text for llama.cpp and whisper.cpp is in each submodule's `LICENSE`. Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0 . CC BY-SA 4.0: https://creativecommons.org/licenses/by-sa/4.0/ . Unicode License v3: https://www.unicode.org/license.txt
