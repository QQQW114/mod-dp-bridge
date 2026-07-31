plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":bridge-model"))
    implementation(project(":bridge-converter"))
    implementation(project(":bridge-runtime-assets"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
