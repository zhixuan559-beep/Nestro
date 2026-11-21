# Multimodal and Video Generation Pipeline

Branch: `feature/phase2-optimization`

## Implemented in code

- Media providers can be configured independently from the primary chat model. A provider may be video-only; a text-only primary model such as DeepSeek does not need to be replaced.
- Each media provider stores its API key through `LocalVault`; provider metadata and endpoint configuration remain in app preferences.
- Video generation configuration includes model ID, submit path, status path with `{job_id}`, request model/prompt field names, response job/status/result URL field paths, auth header/prefix, and extra JSON request fields.
- `MediaVideoGenerationClient` submits an asynchronous task, polls its status, downloads the completed video over HTTPS, rejects local/private result hosts, disables redirects, and caps the downloaded file at 1 GB.
- `MediaAiTaskWorker` runs generation through WorkManager. The generated file is imported into the current project's media asset library.
- Video tasks persist prompt, status, progress, result asset ID, and parent task ID. A revision creates a new task linked to the completed previous task; it does not overwrite the previous asset.
- Chat tool registration includes `media.provider.list`, `media.video.generate`, and `media.video.status`. The primary chat model can choose a configured video provider separately from its own text model.
- Generated asset IDs are attached to the final chat response as an internal marker. The chat bubble renders a “查看生成视频” action that opens the media editor with the generated asset selected; the editor provides standalone playback.
- If no configured video model exists, the model is instructed to provide a usable video prompt. If the user explicitly asks to use an installed app and Phone Agent is available in the current tool directory, it may attempt that workflow; it must not claim app access or download success without execution results.
- If no video provider is configured, the chat response exposes a shortcut to the media provider setup screen. API keys are entered in the secure settings field rather than copied into ordinary assistant text.

## Native tool calling

`OpenAiClient.completeWithNativeTools` now supports tool-call request/response loops for:
- OpenAI-compatible Chat Completions
- Anthropic Messages (`tool_use` / `tool_result`)
- Gemini Generate Content (`functionCall` / `functionResponse`)

The model makes the tool decision in its normal request. The separate text-protocol decision pass is retained only as a compatibility fallback when the provider explicitly rejects native function calling. Network/authentication failures do not silently fall back and trigger another model request. Tool results are re-injected in the provider's native message format, with bounded rounds and call counts.

## Current limitations / not yet accepted

- The video adapter currently targets configurable asynchronous JSON submit/status/result URL APIs. Providers with materially different signing, multipart, polling, or result formats still need dedicated adapters.
- The generic chat connection test verifies the configured chat/vision endpoint only. It does not prove that a video endpoint works. The first actual video generation request validates the configured video contract and may incur provider charges.
- Video playback is opened in the media editor from the chat action; it is not yet an inline video player embedded inside the message bubble.
- Provider setup is opened from chat through a secure settings shortcut; the API key itself is not collected as ordinary chat text.
- No compile, test, lint, emulator, or real-device acceptance was run as part of this implementation sequence. Do not treat these changes as accepted until the unified validation phase.
