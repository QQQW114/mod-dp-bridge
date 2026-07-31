# JAR + source origin index

`bridge-source-index` is a small, non-executing library for pairing an authoritative built Mod
JAR with a source checkout or source ZIP. It is intentionally separate from the converter and the
runtime extractor; adding the module does not yet change CLI conversion behavior.

## Trust boundary

- Runtime classes and assets are enumerated only from the JAR. A source-only file can never become
  a runtime result.
- ASM reads class structure, `SourceFile`, and `LineNumberTable` attributes. The implementation
  does not put input classes on a classpath, load them, initialize them, reflect over them, or run
  source/build scripts.
- A class is linked by its class-file package plus `SourceFile` name. When debug source metadata is
  absent, the top-level binary class name is a clearly marked fallback.
- A runtime asset is linked only when its JAR-relative path and SHA-256 both equal a file below a
  repository `assets/` directory. Same-path/different-byte and ambiguous matches remain explicit.
- Entry, per-file, and total expanded-byte limits are applied while reading both inputs.

The main API is:

```kotlin
val index = JarSourceIndexer().index(runtimeJar, sourceRepository)
```

`sourceRepository` may be a directory or ZIP. Results contain per-class source paths and line
tables, per-asset hash matches, issues, and aggregate match rates.

## New Horizon 2.2.1 local validation

Inputs audited on 2026-08-01:

- release JAR SHA-256: `4d9c1d036e4bfe59f5c790c7064f776d63ebb951f24b4ab9aae71046e4615c87`
- source ZIP SHA-256: `066a8547f57bbb47e4b4eeb4e5e75ced92dc5ef76eea2a9492cd3f034cc23663`

Results:

| Check | Result |
|---|---:|
| Parsed runtime class files | 1572 / 1572 |
| Class files linked through `SourceFile` | 1572 / 1572 (100%) |
| Distinct linked repository source files | 406 |
| Runtime assets matched by relative path + SHA-256 | 1602 / 1602 (100%) |
| Parse/hash issues | 0 |

`classes.dex` is deliberately excluded because it is executable bytecode, not a Mindustry asset.

Three linked class files have a `LineNumberTable` maximum beyond the corresponding source file's
line count:

```text
newhorizon.expand.block.distribution.liquid.AdaptConduit$AdaptConduitBuild
newhorizon.expand.net.packet.RaidBulletPacket
newhorizon.util.ui.TableFunc$ToolTable
```

Their class-to-file origin is still unambiguous, but the line mismatch is evidence that those
source files were generated, preprocessed, or differ from the exact sources used for the release
JAR. Downstream diagnostics should treat JAR behavior as authoritative and use these source links
only for explanation/location.
