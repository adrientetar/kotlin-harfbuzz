import java.nio.file.Files

import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("java-library")
    alias(libs.plugins.maven.publish)
}

val GROUP: String by project
val VERSION_NAME: String by project

group = GROUP
version = VERSION_NAME

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.jna)
    testImplementation(kotlin("test"))
}

// Platform detection
val osName = providers.systemProperty("os.name").map { it.lowercase() }
val osArch = providers.systemProperty("os.arch").map { it.lowercase() }

val currentOs = osName.map { name ->
    when {
        name.contains("mac") || name.contains("darwin") -> "macos"
        name.contains("windows") -> "windows"
        else -> "linux"
    }
}

val currentArch = osArch.map { arch ->
    when {
        arch == "aarch64" || arch == "arm64" -> "arm64"
        else -> "x64"
    }
}

val platformClassifier = providers.zip(currentOs, currentArch) { os, arch -> "$os-$arch" }

val nativeLibName = currentOs.map { os ->
    when (os) {
        "windows" -> "harfbuzz.dll"
        "macos" -> "libharfbuzz.dylib"
        else -> "libharfbuzz.so"
    }
}

val nativeLibExtension = currentOs.map { os ->
    when (os) {
        "windows" -> "dll"
        "macos" -> "dylib"
        else -> "so"
    }
}

// Directory for platform-specific native library
val nativeLibDir = layout.buildDirectory.dir(platformClassifier.map { "native/$it" })

// HarfBuzz source directory
val harfbuzzDir = layout.projectDirectory.dir("harfbuzz")
val harfbuzzBuildDir = layout.buildDirectory.dir("harfbuzz-build")

// Build HarfBuzz using meson
val mesonSetup by tasks.registering(Exec::class) {
    description = "Configure HarfBuzz build using meson"
    val buildDir = harfbuzzBuildDir.get().asFile
    val sourceDir = harfbuzzDir.asFile

    inputs.dir(sourceDir)
    outputs.file(buildDir.resolve("build.ninja"))

    doFirst {
        if (!sourceDir.exists()) {
            throw GradleException(
                "HarfBuzz source not found. Run: git submodule update --init --recursive"
            )
        }
    }

    workingDir = projectDir
    onlyIf { !buildDir.resolve("build.ninja").exists() }

    if (currentOs.get() == "windows") {
        commandLine(
            "powershell",
            "-NoProfile",
            "-Command",
            """
                ${'$'}ErrorActionPreference = 'Stop'
                ${'$'}buildDir = '${buildDir.absolutePath}'
                ${'$'}sourceDir = '${sourceDir.absolutePath}'
                if (Test-Path "${'$'}buildDir") { Remove-Item -Recurse -Force "${'$'}buildDir" }
                meson setup "${'$'}buildDir" "${'$'}sourceDir" -Dglib=disabled -Dgobject=disabled -Dcairo=disabled -Dfreetype=disabled -Dicu=disabled --default-library=shared --vsenv
            """.trimIndent(),
        )
    } else {
        commandLine(
            "bash",
            "-c",
            """
                rm -rf "${buildDir.absolutePath}"
                meson setup "${buildDir.absolutePath}" "${sourceDir.absolutePath}" \
                    -Dglib=disabled \
                    -Dgobject=disabled \
                    -Dcairo=disabled \
                    -Dfreetype=disabled \
                    -Dicu=disabled \
                    --default-library=shared
            """.trimIndent(),
        )
    }
}

val mesonCompile by tasks.registering(Exec::class) {
    description = "Compile HarfBuzz using meson"
    val buildDir = harfbuzzBuildDir.get().asFile
    val sourceDir = harfbuzzDir.asFile

    dependsOn(mesonSetup)
    inputs.dir(sourceDir)
    outputs.files(fileTree(buildDir.resolve("src")) {
        // Track the actual produced library file, not symlinks (Gradle can error on broken symlinks).
        include("harfbuzz.dll", "libharfbuzz.0.dylib", "libharfbuzz.so.0.*")
        exclude { element -> Files.isSymbolicLink(element.file.toPath()) }
    })

    workingDir = projectDir

    if (currentOs.get() == "windows") {
        commandLine(
            "powershell",
            "-NoProfile",
            "-Command",
            """
                ${'$'}ErrorActionPreference = 'Stop'
                ${'$'}buildDir = '${buildDir.absolutePath}'
                meson compile -C "${'$'}buildDir" -v
                Write-Host "=== Built library files ==="
                ${'$'}dlls = Get-ChildItem -Path "${'$'}buildDir\\src\\*harfbuzz*.dll" -ErrorAction SilentlyContinue
                if (${'$'}dlls) { ${'$'}dlls } else { Write-Host "No DLL files found" }
            """.trimIndent(),
        )
    } else {
        commandLine(
            "bash",
            "-c",
            """
                meson compile -C "${buildDir.absolutePath}" -v
                echo "=== Built library files ==="
                shopt -s nullglob
                files=( "${buildDir.absolutePath}/src/"*harfbuzz*.so* "${buildDir.absolutePath}/src/"*harfbuzz*.dylib )
                if [ ${'$'}{#files[@]} -gt 0 ]; then
                  ls -la "${'$'}{files[@]}"
                else
                  echo "No harfbuzz files found"
                fi
            """.trimIndent(),
        )
    }
}

val buildHarfBuzz by tasks.registering {
    description = "Build HarfBuzz native library using meson"
    dependsOn(mesonSetup, mesonCompile)
}

val copyNativeLibrary by tasks.registering(Copy::class) {
    description = "Copy the built native library to platform-specific directory"
    dependsOn(buildHarfBuzz)

    from(harfbuzzBuildDir.map { it.dir("src") }) {
        // Only include the actual library file (not symlinks or subset library)
        include("libharfbuzz.0.dylib")   // macOS versioned
        include("libharfbuzz.so.0.*")    // Linux versioned (actual file; excludes symlinks)
        include("harfbuzz.dll")          // Windows (MSVC)
        exclude { element -> Files.isSymbolicLink(element.file.toPath()) }
    }
    into(nativeLibDir)

    // Rename to standard name
    rename { nativeLibName.get() }
}

val verifyNativeLibrary by tasks.registering {
    description = "Verify the expected native library exists and is not a symlink"
    dependsOn(copyNativeLibrary)

    doLast {
        val expectedFile = nativeLibDir.get().asFile.resolve(nativeLibName.get())
        if (!expectedFile.exists()) {
            throw GradleException("Native library not found: ${expectedFile.absolutePath}")
        }
        if (!expectedFile.isFile) {
            throw GradleException("Native library path is not a file: ${expectedFile.absolutePath}")
        }
        if (Files.isSymbolicLink(expectedFile.toPath())) {
            throw GradleException("Native library must not be a symlink: ${expectedFile.absolutePath}")
        }
    }
}

val prepareNative by tasks.registering {
    description = "Prepare native libraries for current platform"
    dependsOn(copyNativeLibrary)
}

// Copy native library to resources for testing
val copyNativeLibraryForTest by tasks.registering(Copy::class) {
    description = "Copy native library to resources for testing"
    dependsOn(verifyNativeLibrary)
    from(nativeLibDir)
    into(layout.buildDirectory.dir(platformClassifier.map { "resources/main/natives/$it" }))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(copyNativeLibraryForTest)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    dependsOn(copyNativeLibraryForTest)
}

// Base JAR with only Kotlin code (no native libraries)
tasks.jar {
    archiveClassifier.set("")
    exclude("natives/**")
}

// Platform-specific JAR containing only the native library
val nativeJar by tasks.registering(Jar::class) {
    dependsOn(verifyNativeLibrary)
    archiveClassifier.set(platformClassifier)
    from(nativeLibDir) {
        into(platformClassifier.map { "natives/$it" })
    }
}

// Platform-specific native JARs (used by CI publish job when multiple native libs are present)
val currentNativeClassifier = platformClassifier.get()
val allNativeClassifiers = listOf(
    "macos-arm64",
    "macos-x64",
    "linux-x64",
    "linux-arm64",
    "windows-x64",
    "windows-arm64",
)

val nativeJarsByClassifier: Map<String, TaskProvider<Jar>> =
    allNativeClassifiers
        .filter { it != currentNativeClassifier }
        .associateWith { classifier ->
            tasks.register<Jar>("nativeJar_${classifier.replace('-', '_')}") {
                archiveClassifier.set(classifier)
                val dir = layout.buildDirectory.dir("native/$classifier")
                from(dir) {
                    into("natives/$classifier")
                }
                onlyIf { dir.get().asFile.exists() && (dir.get().asFile.listFiles()?.isNotEmpty() == true) }
            }
        }

tasks.named("build") {
    dependsOn(nativeJar)
}

// Hook into vanniktech maven-publish to add native JARs as additional artifacts
publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(nativeJar)
        if (providers.environmentVariable("CI").isPresent) {
            nativeJarsByClassifier.values.forEach { artifact(it) }
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("CI").isPresent) {
        signAllPublications()
    }
}
