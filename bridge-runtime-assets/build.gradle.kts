plugins {
    kotlin("jvm")
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
