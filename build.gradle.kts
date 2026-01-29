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

tasks.test {
    useJUnitPlatform()
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
                "HarfBuzz source not found. Run: git clone --depth 1 https://github.com/harfbuzz/harfbuzz.git"
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
                meson setup "${'$'}buildDir" "${'$'}sourceDir" -Dglib=disabled -Dgobject=disabled -Dcairo=disabled -Dfreetype=disabled -Dicu=disabled --default-library=shared
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
        include("*.dll", "*.dylib", "*.so.*")
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
                files=("${buildDir.absolutePath}/src/"*harfbuzz*.so* "${buildDir.absolutePath}/src/"*harfbuzz*.dylib)
                if [ ${#files[@]} -gt 0 ]; then
                  ls -la "${files[@]}"
                else
                  echo "No harfbuzz files found"
                fi
            """.trimIndent(),
        )
    }
}

tasks.register("buildHarfBuzz") {
    description = "Build HarfBuzz native library using meson"
    dependsOn(mesonSetup, mesonCompile)
}

tasks.register<Copy>("copyNativeLibrary") {
    description = "Copy the built native library to platform-specific directory"
    dependsOn("buildHarfBuzz")
    
    from(harfbuzzBuildDir.map { it.dir("src") }) {
        // Only include the actual library file (not symlinks or subset library)
        include("libharfbuzz.0.dylib")   // macOS versioned
        include("libharfbuzz.so.0")      // Linux versioned (actual file)
        include("libharfbuzz-0.dll")     // Windows versioned
    }
    into(nativeLibDir)
    
    // Rename to standard name
    rename { nativeLibName.get() }
}

tasks.register("prepareNative") {
    description = "Prepare native libraries for current platform"
    dependsOn("copyNativeLibrary")
}

// Copy native library to resources for testing
tasks.register<Copy>("copyNativeLibraryForTest") {
    description = "Copy native library to resources for testing"
    dependsOn("copyNativeLibrary")
    from(nativeLibDir)
    into(layout.buildDirectory.dir(platformClassifier.map { "resources/main/natives/$it" }))
}

tasks.named("processResources") {
    dependsOn("copyNativeLibraryForTest")
}

tasks.test {
    dependsOn("copyNativeLibraryForTest")
}

// Base JAR with only Kotlin code (no native libraries)
tasks.jar {
    archiveClassifier.set("")
    exclude("natives/**")
}

// Platform-specific JAR containing only the native library
val nativeJar by tasks.registering(Jar::class) {
    dependsOn("copyNativeLibrary")
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
