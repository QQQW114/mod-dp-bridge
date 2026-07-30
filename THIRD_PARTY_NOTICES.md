# Third-party notices

This file records third-party material that is bundled with, used by, or used
to validate `mod-dp-bridge`. It is informational and does not replace the
license text that applies to each component.

`mod-dp-bridge` itself is distributed under GNU GPL version 3; see
[`LICENSE`](LICENSE). The names and trademarks of upstream projects remain the
property of their respective owners. This project is not an official
Mindustry project and is not endorsed by the upstream authors.

## Mindustry v159.7 reference material

- Upstream: [Anuken/Mindustry](https://github.com/Anuken/Mindustry)
- Version audited: tag `v159.7`
- Upstream commit: `c9686eb5d0ae5dd47ee02c40f99f7d5018ccbc8c`
- License: GNU GPL version 3
- Upstream license: <https://github.com/Anuken/Mindustry/blob/v159.7/LICENSE>

The following PNG files are unmodified copies of Mindustry v159.7 turret-base
sprites. Their SHA-256 hashes were checked against the files under
`core/assets-raw/sprites/blocks/turrets/bases/` at the commit above:

```text
bridge-converter/src/main/resources/io/github/moddpbridge/converter/mindustry-v159/turret-bases/block-1.png
bridge-converter/src/main/resources/io/github/moddpbridge/converter/mindustry-v159/turret-bases/block-2.png
bridge-converter/src/main/resources/io/github/moddpbridge/converter/mindustry-v159/turret-bases/block-3.png
bridge-converter/src/main/resources/io/github/moddpbridge/converter/mindustry-v159/turret-bases/block-4.png
bridge-converter/src/main/resources/io/github/moddpbridge/converter/mindustry-v159/turret-bases/reinforced-block-2.png
bridge-converter/src/main/resources/io/github/moddpbridge/converter/mindustry-v159/turret-bases/reinforced-block-3.png
bridge-converter/src/main/resources/io/github/moddpbridge/converter/mindustry-v159/turret-bases/reinforced-block-4.png
bridge-converter/src/main/resources/io/github/moddpbridge/converter/mindustry-v159/turret-bases/reinforced-block-5.png
```

The target compatibility data and conversion rules were also developed by
inspecting and testing against Mindustry v159.7 source, particularly
`ContentParser.java`, `DataPatcher.java`, `ClassMap.java`, content classes and
the vanilla content catalogs. Relevant project files include:

```text
config/mindustry-v1597-classmap.json
config/java-to-dp-v1597.hjson
bridge-java-static/src/main/kotlin/io/github/moddpbridge/javastatic/MindustryJavaMappings.kt
bridge-target-1597/
```

These project-authored tables and adapters are not a complete copy of
Mindustry and do not include a Mindustry executable. The project as a whole is
GPLv3, which is compatible with the bundled GPLv3 reference sprites and any
copyrightable source-derived compatibility data.

## Runtime libraries

Gradle resolves the following libraries from Maven Central. They are not
vendored as source in this repository, but they are included as JAR files in a
standard `bridge-cli` application distribution.

| Component | Version | License | Upstream |
|---|---:|---|---|
| Kotlin standard library | 2.3.20 | Apache-2.0 | <https://github.com/JetBrains/kotlin> |
| JetBrains Java annotations | 13.0 | Apache-2.0 | <https://github.com/JetBrains/java-annotations> |
| kotlinx.serialization | 1.10.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.serialization> |
| picocli | 4.7.6 | Apache-2.0 | <https://github.com/remkop/picocli> |
| Hjson Java | 3.1.0 | MIT | <https://github.com/hjson/hjson-java> |
| Apache Commons Compress | 1.27.1 | Apache-2.0 | <https://commons.apache.org/proper/commons-compress/> |
| Apache Commons Codec | 1.17.1 | Apache-2.0 | <https://commons.apache.org/proper/commons-codec/> |
| Apache Commons IO | 2.16.1 | Apache-2.0 | <https://commons.apache.org/proper/commons-io/> |
| Apache Commons Lang | 3.16.0 | Apache-2.0 | <https://commons.apache.org/proper/commons-lang/> |
| JavaParser | 3.26.3 | Apache-2.0 **or** LGPL-3.0; this project uses the Apache-2.0 option | <https://github.com/javaparser/javaparser> |

The Apache License 2.0 text is reproduced in
[`third-party/licenses/Apache-2.0.txt`](third-party/licenses/Apache-2.0.txt).
The Hjson MIT notice is reproduced in
[`third-party/licenses/Hjson-MIT.txt`](third-party/licenses/Hjson-MIT.txt).
Apache Commons JARs additionally contain their own `META-INF/LICENSE.txt` and
`META-INF/NOTICE.txt`; those entries must remain intact when redistributing
the JARs.

## Build and test tooling

The repository includes the Gradle Wrapper 9.2.1 (`gradle-wrapper.jar` and
launcher scripts), licensed under Apache-2.0. The build also resolves the
Kotlin Gradle plugins under Apache-2.0 and JUnit Jupiter 5.11.4 under
EPL-2.0. Test-only dependencies are not included in the normal CLI runtime
distribution.

- Gradle: <https://github.com/gradle/gradle>
- Kotlin: <https://github.com/JetBrains/kotlin>
- JUnit 5: <https://github.com/junit-team/junit5>
- Eclipse Public License 2.0: <https://www.eclipse.org/legal/epl-2.0/>

## Test fixtures and local reference corpora

Small fixtures under `fixtures/self-authored/` and `fixtures/negative/` were
created for this project and dedicated to the public domain under CC0-1.0.
Their local `LICENSE.txt` files contain the applicable notice.

The following third-party corpora are **not part of the public repository or
release artifacts**:

- Saturation Firepower (`RA2EXE/Saturation-Firepower`, author metadata
  `RAdea.exe`) is a GPLv3 Mod used as an opt-in, local end-to-end reference.
  The local source tree is under `资源参考/Saturation-Firepower-main/`, which
  is excluded by `.gitignore`. No Saturation source code, sprites, sounds or
  converted output should be committed or shipped by this repository.
- The MDT `external-cp` upload corpus has no uniform, verified redistribution
  license. Only a metadata/hash manifest is stored in
  `fixtures/external-cp/`; the referenced uploads themselves must remain
  local and must not be redistributed without permission from their authors.

Saturation Firepower upstream:
<https://github.com/RA2EXE/Saturation-Firepower>

## Converted output

Running this tool does not erase or replace the license of an input Mod. A
generated DP can contain copied or transformed source, sprites, audio and
other assets from that Mod. Anyone publishing a generated DP is responsible
for complying with the input Mod's license and all third-party asset terms.
The GPLv3 license of `mod-dp-bridge` does not, by itself, grant rights to
redistribute arbitrary input assets.
