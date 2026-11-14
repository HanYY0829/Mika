# Mika Voice Edition

This branch adds an opt-in microphone foreground service. In Settings, tap **Hello Mika** to start or stop listening. The service recognizes `你好米卡` locally, records a command until speech ends (up to 15 seconds), sends it through Mika's configured cloud speech-to-text provider, and submits the resulting text through the existing `TaskService`. Voice follow-ups stay in the same conversation; say `新任务` after the wake phrase or create a blank chat in Mika to start another. A wake while a task asks a question answers the same run, and a wake while it runs steers the same conversation. Confirmation and takeover still require Mika's existing on-screen controls.

Before enabling it, configure a provider or Doubao speech model under **Settings → Models → Speech to text** and grant microphone permission. The microphone service starts from a visible Mika screen, keeps a notification with a Stop action, and does not start from a boot receiver. Some Android versions may require tapping **Run** in that notification before a recognized command can start a new foreground task.

Build the separate test app with `:app:assembleDebug -PdevInstall`. Its package name is `com.wangzan101.mika.dev`, so it can be installed beside the original Mika. The test app has separate data and needs its own model and phone-operation setup.

To update a locally built test app, merge the latest upstream `main`, run the voice tests and `:app:assembleDebug -PdevInstall`, then install the new APK with the same debug signing key. A different signing key cannot update the installed test app. Official Mika's in-app updater does not update this separate package.

Bundled dependencies:

- Sherpa-ONNX Android AAR v2.0.1 from the [official release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1), SHA-256 `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`.
- The int8 encoder, int8 joiner, decoder, and token list from [sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20](https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models). `wake/keywords.txt` selects `你好米卡`.

Both the Sherpa-ONNX code and this KWS model are Apache-2.0 licensed; the model author's and Sherpa maintainer's clarification is linked in `licenses/THIRD_PARTY_NOTICES.md`.

Wake recognition, cloud transcription, task launch, the on-screen status overlay, and voice follow-ups have been exercised on the target Realme phone. The overlay follows the spoken task through startup, running, waiting for an answer or confirmation, and completion. Automatic speech ending uses an adaptive pause (shorter after sentence punctuation, longer after an unfinished phrase) with the same 15-second limit. Doubao's definite utterance marker can finish a complete command after local silence; it cannot end an unfinished phrase on its own. Wake audio preroll is capped at three seconds to limit upload latency. The Doubao stream sends buffered preroll audio at a 100 ms packet interval, then returns to 200 ms real-time pacing. OEM battery management and Android's background foreground-service limits can affect reliability.

On the RMX3370, reducing Sherpa to one inference thread and four active search paths kept five out of five single-phrase wake trials successful. `top` samples while the wake service waited were about 42–52% of one CPU core before the change and around 20% after it settled, with occasional spikes. The phone was USB-powered; this is a CPU comparison, not a battery-life measurement.
