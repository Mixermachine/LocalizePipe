# Plugin Testing Plan

## 1. Scope and Quality Gates

The plugin must be safe for XML edits, predictable across project types, and resilient to provider errors.

Release gates:

- `./gradlew build` passes.
- `./gradlew test` passes.
- `./gradlew verifyPlugin` passes.
- No failing cases in Android-only, KMP-only, and mixed-repo acceptance matrix.

## 2. Test Layers (in execution order)

### A) Unit Tests (fast, mandatory)

Target pure logic and settings behavior.

- `TranslateGemmaLanguageMapperTest`
    - `tr -> tur_Latn`
    - `pt-BR` and `pt_rBR` normalization behavior
    - `zh-CN/zh-TW` script mapping
    - unknown locale returns `null`
- `TranslationSettingsServiceTest`
    - default provider/model/endpoint values
    - provider toggle behavior
    - endpoint/model selection by provider

### B) XML/Validation Unit Tests

Target translation safety checks (to be implemented with translation service).

- placeholder preservation: `%1$s`, `%d`, `{name}`
- XML tag preservation: `<b>`, `<i>`, escaped entities
- rejection paths: malformed XML fragments, dropped placeholders
- provider outputs must be replayed from static fixtures (no live network calls in CI/unit tests)

### C) IntelliJ Platform Integration Tests

Use IntelliJ test framework for PSI and project/VFS behavior.

- scanner discovery for:
    - Android: `src/*/res/values*/strings.xml`
    - KMP: `src/commonMain/composeResources/values*/strings.xml`
- locale qualifier parsing (`values-pt-rBR` shown as `pt-BR`)
- PSI apply flow:
    - create missing locale file
    - insert missing keys
    - replace existing values
    - undo/redo correctness

### D) UI/Workflow Tests

Focus on tool window behavior (manual now, automated later).

- tool window opens on right stripe with custom icon
- status transitions: Idle -> Scanning -> Translating -> Done/Error
- `Apply` disabled until valid proposals exist

## 3. Fixture Matrix

Create reusable fixtures in `src/test/resources/fixtures/`:

- `android-only/`
- `kmp-only/`
- `mixed/`
- `invalid-xml/`
- `ollama/translategemma_4b/` (recorded raw `api/generate` responses)

Each fixture should include base `strings.xml`, at least one locale variant, and expected post-apply output.

## 4. Command Plan

Fast local loop:

- `./gradlew test --tests '*MapperTest' --tests '*SettingsServiceTest'`

Full pre-PR loop:

- `./gradlew clean test verifyPlugin`

Manual smoke before merge:

- `./gradlew runIde`
- Validate one Android sample and one KMP sample in UI.

## 5. Rollout Priorities

1. Add unit tests for mapper/settings first.
2. Add scanner + PSI integration tests once scanner/applier land.
3. Add validation tests with mocked provider responses.
4. Add automated UI workflow checks after screen structure stabilizes.
