plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":bridge-model"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
