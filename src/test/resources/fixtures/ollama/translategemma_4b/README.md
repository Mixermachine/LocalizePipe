# Recorded Ollama Fixtures

These files are static captures from local Ollama `translategemma:4b` and are used by tests.

- Capture date: 2026-02-17
- Endpoint: `http://127.0.0.1:11434/api/generate`
- Mode: `stream=false`

Captured prompts:

- `en_tr_settings.raw.json`: `Translate from eng_Latn to tur_Latn... Text: Settings`
- `en_de_welcome_placeholder.raw.json`: `Translate ... preserve placeholders ... Text: Welcome, %1$s!`
- `en_fr_bold_save.raw.json`: `Translate ... preserve XML tags ... Text: <b>Save</b> now`

Automated tests must only read these static fixtures and must not call live Ollama.
