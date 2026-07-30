plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":bridge-model"))
    implementation(project(":bridge-target-api"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
