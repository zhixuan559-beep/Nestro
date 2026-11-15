# Nestro

Nestro is a local-first, open-source Android AI workspace. It has no Nestro account system.

## Current implementation

- Kotlin + Jetpack Compose Android app.
- OpenAI Chat Completions-compatible model endpoint configuration.
- API key encryption using Android Keystore (AES-GCM).
- Local conversation history and local expert-name presets.
- Dark interface with mint accent.
- GitHub Actions debug APK workflow.

## Configure a model

Open **模型管理** and enter:
- API base URL, for example `https://api.example.com/v1`
- Model identifier supplied by your provider
- Your own API key

The app calls `POST {baseUrl}/chat/completions`. Provider compatibility varies. Keys and conversation data are stored locally on the device; keys are encrypted with Android Keystore. No Nestro account or Nestro-hosted backend is used.

## Not implemented yet

Project-space isolation and file indexing, document ingestion/RAG, full expert prompts and teams, Skill/MCP installation and permission review, Git provider integration, streaming responses, export/restore, AI code-change sandbox, and automated rollback are not yet implemented. They must not be treated as working features.

## Build

Push to `main` to trigger the GitHub Actions Android build. The workflow produces a debug APK artifact. A successful CI build is not a substitute for installation and testing on a physical Samsung device.
