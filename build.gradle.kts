import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.gradleup.shadow") version "9.4.1"
}

group = "com.operator"
version = providers.gradleProperty("pluginVersion").get()
description = "Bedrock-style content pack system for Paper servers"

val paperVersion = providers.gradleProperty("paperVersion").get()
val runVersion = providers.gradleProperty("runVersion").get()
val pluginVersion = version.toString() // captured eagerly so the resource filter stays configuration-cache safe

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

// MyPack only uses the public Paper API (no NMS), so the Mojang-mapped production jar is the most portable
// artifact: it loads on 1.20.5+ without a runtime remap and keeps working on 26.x+ where Spigot-mapped
// plugins are no longer supported.
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

dependencies {
    // Paper API + server internals (compileOnly) through the userdev bundle.
    paperweight.paperDevBundle(paperVersion)

    // Shaded + relocated. SLF4J is provided by the server, so the transitive dependency is excluded.
    implementation("com.zaxxer:HikariCP:5.1.0") {
        exclude(group = "org.slf4j")
    }

    // ---- tests (pure-logic tests; no running server is needed) ----
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The dev bundle is compileOnly, so the API jar must be provided again for the test runtime classpath.
    testImplementation("io.papermc.paper:paper-api:$paperVersion")
    // Drivers that Paper itself ships at runtime; tests need them explicitly.
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.46.0.0")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.16")
}

tasks {
    compileJava {
        options.release = 21
        options.encoding = Charsets.UTF_8.name()
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
    }

    processResources {
        val props = mapOf("version" to pluginVersion)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // `jar` is the thin, unshaded jar; `shadowJar` (no classifier) is the one to install on a server.
    jar {
        archiveClassifier = "plain"
    }

    shadowJar {
        archiveClassifier = ""
        relocate("com.zaxxer.hikari", "com.operator.mypack.libs.hikari")
        manifest {
            attributes("paperweight-mappings-namespace" to "mojang")
        }
    }

    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion(runVersion)
        jvmArgs("-Xms1G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }
}
