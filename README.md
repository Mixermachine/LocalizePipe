# LocalizePipe

LocalizePipe is an IntelliJ plugin that helps you localize `strings.xml` files faster.

It scans your project, finds missing translations, generates suggestions with local AI, and writes them back into your
language files.

## What It Does

- Finds untranslated strings in Android and Compose Multiplatform projects
- Shows missing locales in one place
- Generates translations with local AI (Ollama, OpenAI-compatible servers, or Hugging Face)
- Validates placeholders and XML safety before writing
- Applies changes directly to `strings.xml`

## Why Use It

- Save time on repetitive translation work
- Keep translation flow inside IntelliJ
- Use local models with Ollama, llama.cpp, vLLM, LM Studio, or OpenRouter for privacy and speed

## Ollama Setup (Recommended)

1. Install Ollama

- Download and install from: https://ollama.com

2. Start Ollama

- On most systems, Ollama starts as a background service after install.
- If needed, run:

```bash
ollama serve
```

3. Pull a translation model

- Recommended default:

```bash
ollama pull translategemma:4b
```

4. Keep the default LocalizePipe settings

- Provider: `OLLAMA`
- Base URL: `http://127.0.0.1:11434`
- Model: `translategemma:4b`

## OpenAI-compatible Setup (vLLM, TGI, llama.cpp, LM Studio, OpenRouter)

LocalizePipe can connect to any inference server providing an OpenAI Chat Completions streaming endpoint (`/v1/chat/completions`).

1. Select **OPENAI_COMPATIBLE** as the provider in **Settings -> LocalizePipe**.
2. Configure base URL and model name:
   - **llama.cpp server / LM Studio**: `http://127.0.0.1:8080`, model `translategemma-4b`
   - **vLLM / TGI**: `http://localhost:8000/v1`, model `translategemma-4b`
   - **OpenRouter**: `https://openrouter.ai/api/v1`, model name & API Key
3. (Optional) Enter an **API Key** if your server or proxy requires Bearer token authorization.

## Quick Start

1. Open your project in IntelliJ.
2. Open the **LocalizePipe** tool window (right side).
3. Open **Settings** and confirm:

- Source locale tag (for example `en`)
- Target provider (`OLLAMA`)

4. Click **Rescan**.
5. Click **Translate + Write**.
6. Review the changed `strings.xml` files and commit.

## Supported Resource Layouts

- Android: `*/src/*/res/values*/strings.xml`
- Compose: `*/src/commonMain/composeResources/values*/strings.xml`

## Troubleshooting

- "Ollama unreachable": confirm Ollama is running and URL is `http://127.0.0.1:11434`
- "Model missing": run `ollama pull translategemma:4b`
- Slow responses: try a smaller model or lower load on your machine

## Build From Source

```bash
./gradlew build
```

Run in a sandbox IDE:

```bash
./gradlew runIde
```

## License

This project is licensed under the Apache License 2.0.

See the `LICENSE` file for the full text.
