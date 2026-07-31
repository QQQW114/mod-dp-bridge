plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":bridge-model"))
    implementation(project(":bridge-converter"))
    implementation(project(":bridge-source-index"))
    implementation(project(":bridge-target-api"))
    implementation(project(":bridge-target-1597"))
    implementation(project(":bridge-runtime-mapper"))
    // runtime-convert --source directly invokes the non-executing runtime-guided Java candidate
    // builder before the official DataPatcher filter.
    implementation(project(":bridge-java-static"))
    // The CLI launches this main class in a child JVM. Keeping it runtime-only prevents the
    // extractor implementation from becoming an in-process API or execution path.
    runtimeOnly(project(":bridge-runtime-extractor"))
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

application {
    mainClass.set("io.github.moddpbridge.cli.MainKt")
}

distributions {
    main {
        contents {
            from(rootProject.file("LICENSE"))
            from(rootProject.file("THIRD_PARTY_NOTICES.md"))
            from(rootProject.file("third-party/licenses")) {
                into("third-party/licenses")
            }
        }
    }
}
