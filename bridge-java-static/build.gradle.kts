plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":bridge-converter"))
    implementation("com.github.javaparser:javaparser-core:3.26.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
