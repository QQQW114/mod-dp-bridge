plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":bridge-model"))
    implementation(project(":bridge-converter"))
    implementation(project(":bridge-target-api"))
    implementation(project(":bridge-target-1597"))
    runtimeOnly(project(":bridge-java-static"))
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
