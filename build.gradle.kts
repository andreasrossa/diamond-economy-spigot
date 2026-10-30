plugins {
    kotlin("jvm") version "2.4.21"
}

group = "dev.diamondecon"
version = "0.1.0"

val kotlinVersion = "2.4.21"
val postgresVersion = "42.7.14"
val exposedVersion = "1.6.0"
val hikariVersion = "7.1.0"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:26.3-R0.1-SNAPSHOT")
    // Downloaded by Spigot at startup via plugin.yml `libraries`, so not bundled into the jar.
    compileOnly(kotlin("stdlib"))
    compileOnly("org.postgresql:postgresql:$postgresVersion")
    compileOnly("org.jetbrains.exposed:exposed-core:$exposedVersion")
    compileOnly("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    compileOnly("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    compileOnly("com.zaxxer:HikariCP:$hikariVersion")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    // Shows Testcontainers' log output (e.g. why it can't reach Docker).
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

// Tests run outside Spigot, so they need the provided dependencies on their classpath.
configurations.testImplementation {
    extendsFrom(configurations.compileOnly.get())
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    // Database tests start a throwaway postgres:16 container through Testcontainers, so Docker must be running.
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand(
            "version" to project.version,
            "kotlinVersion" to kotlinVersion,
            "postgresVersion" to postgresVersion,
            "exposedVersion" to exposedVersion,
            "hikariVersion" to hikariVersion,
        )
    }
}
