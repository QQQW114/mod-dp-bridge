import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    kotlin("jvm") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
}

allprojects {
    group = "io.github.moddpbridge"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            // JDK 21 keeps Gradle worker argfiles UTF-8 on Windows paths containing Chinese
            // characters, while emitted bytecode remains Java 17 compatible.
            jvmToolchain(21)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
    }
}
