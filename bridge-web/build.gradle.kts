plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":bridge-model"))

    // The web process deliberately starts the converter in a separate JVM. Keeping
    // the CLI as a runtime dependency places it and all converter modules in the
    // application distribution without coupling the HTTP layer to converter APIs.
    runtimeOnly(project(":bridge-cli"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    systemProperty("modDpBridge.testRuntimeClasspath", configurations.runtimeClasspath.get().asPath)
}

application {
    mainClass.set("io.github.moddpbridge.web.MainKt")
    applicationName = "mod-dp-bridge-web"
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
    )
}

distributions {
    main {
        contents {
            from(rootProject.file("start-web.bat"))
            from(rootProject.file("scripts/start-web.ps1")) {
                into("scripts")
            }
            from(rootProject.file("README.md"))
            from(rootProject.file("CHANGELOG.md"))
            from(rootProject.file("docs/WEB_UI.md")) {
                into("docs")
            }
            from(rootProject.file("docs/TESTING.md")) {
                into("docs")
            }
            from(rootProject.file("docs/CLI_RUNTIME_INTEGRATION.md")) {
                into("docs")
            }
            from(rootProject.file("LICENSE"))
            from(rootProject.file("THIRD_PARTY_NOTICES.md"))
            from(rootProject.file("third-party/licenses")) {
                into("third-party/licenses")
            }
        }
    }
}
