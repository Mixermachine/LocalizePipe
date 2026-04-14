# Source Change Tracking Plan

## Summary

Store LocalizePipe source hashes in a dedicated JSON metadata file per resource-root family instead of writing attributes into translated `strings.xml` entries.

The metadata file lives next to the resource root:

- Android: `src/<sourceSet>/localizepipe-source-hashes.json` next to `res/`
- Compose: `src/commonMain/localizepipe-source-hashes.json` next to `composeResources/`

If the current source text hash differs from the stored hash for a localized entry, the row is marked as `SOURCE_CHANGED` and stays actionable in the normal translation flow.

## Chosen Design

- Keep project-scoped setting `trackSourceChanges`, default `true`
- Keep row status `SOURCE_CHANGED`
- Keep hash algorithm: SHA-256 over UTF-8 source text, stored as first 16 lowercase hex chars
- Store hashes per resource-root family, per locale, per string key in JSON
- Keep older translations without metadata as non-stale until populated
- Populate action adds only missing hashes and never overwrites existing ones
- Remove action clears LocalizePipe hash metadata files for the supported resource roots

## Implementation Changes

### Metadata storage

- Add a source-change metadata store for parsing and serializing `localizepipe-source-hashes.json`
- Canonical path is the sibling of `res/` or `composeResources/`
- JSON shape:
  - top-level key = locale tag
  - nested key = string resource key
  - value = source hash
- Delete the metadata file when the last stored hash entry is removed

### Scan and apply flow

- Scanner reads source hashes from the per-root metadata file instead of XML attributes
- Translation writes update or remove the stored hash entry depending on `trackSourceChanges`
- Deleting translations also removes the corresponding stored hash entry
- XML writing returns to plain `<string>` entry updates without LocalizePipe-specific attributes

### Settings and maintenance actions

- Keep the checkbox label `Track source text changes`
- Rename maintenance buttons to:
  - `Populate source change hashes`
  - `Remove source change hashes`
- Populate scans supported resource roots and adds hashes only for localized entries that already exist and do not yet have stored hashes
- Remove clears all stored hashes in the supported resource roots

## Test Plan

- Hash tests for stability and change detection
- Metadata store tests for parse, serialize, upsert, remove, and empty-file behavior
- XML extraction tests to confirm source-hash parsing is no longer needed
- Apply tests to confirm XML updates stay plain and source-hash metadata logic moved out of XML
- Grouped-row tests to keep `SOURCE_CHANGED` prioritized as an actionable state

## Assumptions and Defaults

- Metadata files are repo-tracked and shared between developers
- There is one metadata file per resource root, not one project-wide file
- Existing inline XML attributes, if still present, are ignored by the new implementation
