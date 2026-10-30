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
}

kotlin {
    jvmToolchain(25)
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
