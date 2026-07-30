# Java static export pipeline contract

This document describes the deterministic integration boundary between Java source analysis and
the existing Mod/CP/Data-Pack converter. Input code is **never compiled, loaded, or executed**.

## Lifecycle

1. `SafeSourceReader` applies archive/path/size/compression checks and creates an in-memory source
   snapshot.
2. `SourceDetector` identifies the source and original Mindustry mod namespace.
3. `BridgeConverter` runs every `StaticSourceExporter` (explicitly supplied or discovered through
   `ServiceLoader`).
4. Exported files enter the normal `ConversionPlanner` candidate set. They receive the same path
   collision, HJSON parse/normalization, asset-reference, namespace, icon and packaging checks as
   declarative files.
5. A claimed Java file gets the exporter's `StaticSourceOutcome` instead of the generic
   `UNSUPPORTED` result. Unclaimed Java/JS/Kotlin files still produce `MOD_CODE_NOT_EXECUTED`.
6. `ConversionReport.contentResults` records each logical declaration independently, including
   declarations for which no output could be generated.

## SPI

The public API is in:

`bridge-converter/src/main/kotlin/io/github/moddpbridge/converter/StaticSourceExporter.kt`

An implementation returns `StaticExportResult` containing:

- `generatedFiles`: target-relative Data Assets files;
- `sourceOutcomes`: exactly one final file-level result for every claimed source file;
- `contentResults`: declaration-level `converted`, `degraded`, `excluded`, `unsupported`, or
  `failed` results;
- structured `diagnostics`, ordered `logs`, and string `metadata`.

Generated paths use the final Data Assets layout, for example:

```text
content/items/alloy.hjson
content/blocks/heavy-turret.hjson
patches/vanilla-overrides.hjson
```

Use local, unprefixed filenames (`alloy.hjson`, not `dp-alloy.hjson`); Mindustry registers the
result as `dp-alloy`. Java exporters should normally keep the default
`StaticOutputNamespace.SOURCE` so original names pass through the same field-aware namespace
rewriter as ordinary mods. This matters for fields such as `Weapon.name`: the content parser adds
the current `dp-` namespace itself, so writing a final `dp-*` value there can produce `dp-dp-*`.
Use `TARGET` only when every reference in the generated file is deliberately final and has already
accounted for ContentParser's automatic prefixing rules.

## Reporting rules

- Never silently omit a recognized declaration or property.
- Emit a loadable parent-class approximation as `DEGRADED`, not `CONVERTED`.
- Put the omitted callback/property/type in a diagnostic and link its code from `ContentResult`.
- Use `UNSUPPORTED` when no safe DP representation can be emitted.
- Use `EXCLUDED` only for explicit project-scope exclusions such as planets, sectors, maps, tech
  trees, GUI and networking.
- A single Java file may list many `outputPaths`; reports preserve all of them.

## Service registration

Provider modules register the implementation class in:

`META-INF/services/io.github.moddpbridge.converter.StaticSourceExporter`

The provider class must exist in the runtime artifact. The CLI includes exporter implementations
with `runtimeOnly`; an invalid service entry is a packaging defect and must be caught by CLI smoke
testing in addition to unit tests.

## 2026-07-30 integration validation

- `JavaStaticServiceLoaderTest` verifies the provider resource, provider construction, automatic
  discovery and end-to-end generated content injection.
- Installed-distribution smoke test converted one Java source into Item, Liquid and Status HJSON:
  3/3 declarations converted, no errors, and no `MOD_CODE_NOT_EXECUTED` fallback.
- Saturation Firepower source-tree smoke output:
  - 1,764 scanned source files;
  - 1,628 copied/normalized non-content assets;
  - 354 declaration-level results;
  - 43 emitted content files: 28 converted and 15 degraded;
  - 311 Unit/Block declarations explicitly reported unsupported by the current first slice;
  - 25 active Java source files claimed by the exporter;
  - file-result coverage remained exactly 1,764/1,764 with no duplicate source result;
  - every diagnostic code linked from `fileResults` or `contentResults` resolved to a diagnostic;
  - no remaining `sfire-mod-*` or accidental `dp-dp-*` strings in generated content.

Java snippets stored below `assets/` and `gradle/wrapper/gradle-wrapper.jar` are treated as inactive
repository resources/build tooling, not executable Mod code. Actual `assets/scripts/` files remain
executable-source warnings. Runtime/server/client loading of this new 43-content package is still
pending external validation.
