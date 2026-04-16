import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("fabric-loom") version "1.8-SNAPSHOT"
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    `maven-publish`
}

version = property("mod_version")!!
group = property("maven_group")!!

base {
    archivesName.set(property("archives_base_name") as String)
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("booger") {
            sourceSet(sourceSets.main.get())
        }
    }

    // Enable mixin hotswap in dev
    runs {
        named("client") {
            vmArgs(
                // ZGC: generational, low-pause — beats G1GC for sustained gameplay
                "-XX:+UseZGC",
                "-XX:+ZGenerational",
                "-XX:ZUncommitDelay=1",
                // Disable biased locking (deprecated Java 21, but explicit)
                "-XX:+UnlockExperimentalVMOptions",
                // Sodium requires this for buffer management
                "-XX:MaxDirectMemorySize=2G",
                // Disable JIT compile threshold reduction (avoids cold-start hitching)
                "-XX:CompileThreshold=1500",
                // Enable JVMTI for our profiling hooks
                "-Dfabric.development=true",
                "-Dbooger.debug=true"
            )
        }
    }
}

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://api.modrinth.com/maven") // Sodium
    mavenCentral()
}

val kotlinVersion = "2.0.21"
val kotlinxSerializationVersion = "1.7.3"
val kotlinxCoroutinesVersion = "1.9.0"

dependencies {
    // Minecraft + Fabric
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")

    // Kotlin
    implementation("net.fabricmc:fabric-language-kotlin:1.12.3+kotlin.$kotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    // Sodium — mandatory, bundled performance layer
    modImplementation("maven.modrinth:sodium:${property("sodium_version")}")

    // Config serialization
    implementation("com.google.code.gson:gson:2.11.0")

    // Annotation processor for mixins
    annotationProcessor("net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7")
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
        // Enable all warnings — we want clean code
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    }

    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.addAll(
                "-Xjvm-default=all",
                "-opt-in=kotlin.RequiresOptIn",
                // Inline classes for zero-overhead HUD coordinate wrappers
                "-Xinline-classes"
            )
        }
    }

    jar {
        from("LICENSE")
    }

    processResources {
        inputs.property("version", project.version)
        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to project.version))
        }
    }
}

java {
    // Toolchain: Gradle will use (or auto-provision) Java 21 regardless
    // of what JDK the CI runner has on PATH. This is the correct fix
    // for "Gradle is using 17" errors on GitHub Actions.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}
