import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20"
    application
}

group = "wikitrend"
version = "0.1.0"

repositories {
    mavenCentral()
}

// All versions pinned so a fresh build resolves the same artifacts.
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jfree:jfreechart:1.5.6")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    testImplementation(kotlin("test"))
}

// Target JDK 17 bytecode but build with whatever JDK >= 17 is installed (no toolchain download).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("wikitrend.MainKt")
    applicationName = "wikitrend"
    // Headless AWT for chart rendering; C1-only JIT for fast CLI startup.
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-XX:TieredStopAtLevel=1", "-Xshare:auto")
}

tasks.test {
    useJUnitPlatform()
}
