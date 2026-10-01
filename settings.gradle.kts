plugins {
    // Lets Gradle auto-provision the JDK 21 toolchain on machines that only have another JDK installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "MyPack"
