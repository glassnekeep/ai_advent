plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "1.9.23"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    implementation("io.ktor:ktor-client-cio:3.3.3")
    implementation("io.ktor:ktor-server-cio:3.3.3")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.13.0")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.13.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
application {
    mainClass.set("org.example.MainKt")
}

kotlin {
    jvmToolchain(21)
}
