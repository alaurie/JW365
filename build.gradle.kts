plugins {
    application
    id("org.openjfx.javafxplugin")
}

val rawVersion: String = (project.findProperty("appVersion") as? String)
    ?: System.getenv("APP_VERSION")
    ?: "0.2.2"

val cleanVersion: String = rawVersion.removePrefix("v").trim()

group = "org.alaurie"
version = cleanVersion

repositories {
    maven { url = uri("offline-repository") }
    mavenCentral()
}

val targetJavaVersion = (project.findProperty("javaVersion") as? String)?.toIntOrNull()
    ?: 25

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
}
javafx {
    version = "25.0.4"
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.web")
}
// JVM tuning flags — single source of truth for dev run, jpackage, and JavaExec tasks
val jvmFlags = listOf(
    "--enable-native-access=ALL-UNNAMED",
    "-Xms24m",
    "-Xmx192m",
    "-XX:ReservedCodeCacheSize=64m",
    "-XX:CICompilerCount=2",
    "-XX:+UseSerialGC",
    "-XX:MinHeapFreeRatio=10",
    "-XX:MaxHeapFreeRatio=20"
)

application {
    mainClass.set("org.alaurie.jw365.gui.Jw365Main")
    applicationDefaultJvmArgs = jvmFlags
}

dependencies {
    implementation("tools.jackson.core:jackson-databind:3.2.2")
    implementation("com.microsoft.azure:msal4j:1.26.0")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}
tasks.withType<Test> {
    useJUnitPlatform()
}

val versionExpansion = mapOf("version" to cleanVersion)
tasks.named<ProcessResources>("processResources") {
    filesMatching("**/version.properties") {
        expand(versionExpansion)
    }
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast { windowsScript.delete() }
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    jvmArgs = jvmFlags
}
// --------------------------------------------------------------------------
// Packaging: jlink minimal runtime + jpackage .deb, .rpm & portable tarball
// --------------------------------------------------------------------------

val javaHome: String = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile.absolutePath
val jpackageJvmOptions = jvmFlags.flatMap { listOf("--java-options", it) }
val iconFile = file("src/main/resources/org/alaurie/jw365/gui/icon.png")
val resourceDir = file("packaging")
val inputDir = layout.buildDirectory.dir("install/jw365/lib")
val flatpakManifest = file("io.github.alaurie.JW365.yml")
val flatpakSourceDir = layout.buildDirectory.dir("flatpak-source")
val flatpakBuildDir = layout.buildDirectory.dir("flatpak")
val flatpakRepoDir = layout.buildDirectory.dir("flatpak-repo")
val flatpakBundleFile = layout.buildDirectory.file("distributions/jw365.flatpak")

tasks.register<Exec>("flatpakBuild") {
    group = "distribution"
    description = "Builds the Flatpak application"
    dependsOn("test")
    environment("APP_VERSION", cleanVersion)
    val sourceDir = flatpakSourceDir.get().asFile
    doFirst {
        sourceDir.deleteRecursively()
        copy {
            from(projectDir)
            into(sourceDir)
            exclude(
                ".git/**",
                ".flatpak-builder/**",
                ".gradle/**",
                ".idea/**",
                ".vscode/**",
                "bin/**",
                "build/**",
                "build-flatpak*/**",
                "flatpak-repo*/**",
                "repo/**",
                "**/*.iml",
                "Project_Default.xml"
            )
        }
    }
    commandLine(
        "flatpak-builder",
        "--force-clean",
        "--disable-cache",
        "--disable-rofiles-fuse",
        "--repo=${flatpakRepoDir.get().asFile.absolutePath}",
        flatpakBuildDir.get().asFile.absolutePath,
        sourceDir.resolve(flatpakManifest.name).absolutePath
    )
    doLast {
        val appRoot = flatpakBuildDir.get().asFile.resolve("files")
        val required = mutableListOf(
            appRoot.resolve("runtime/bin/java"),
            appRoot.resolve("runtime/lib/modules"),
            appRoot.resolve("lib/jw365-$cleanVersion.jar")
        )
        if (appRoot.resolve("lib").listFiles()?.none {
                it.isFile && it.name.startsWith("javafx-web-") && it.name.endsWith(".jar")
            } != false) {
            required += appRoot.resolve("lib/javafx-web-*.jar")
        }
        val missing = required.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Flatpak staging is missing required artifacts: " +
                    missing.joinToString(", ") { it.relativeTo(appRoot).path }
            )
        }
    }
}

tasks.register("validateFlatpakArtifacts") {
    group = "distribution"
    description = "Validates the Java runtime and application libraries for Flatpak"
    dependsOn("createRuntimeImage", "installDist")
    doLast {
        val runtimeDir = layout.buildDirectory.dir("runtime").get().asFile
        val appLibDir = layout.buildDirectory.dir("install/jw365/lib").get().asFile
        val required = mutableListOf(
            runtimeDir.resolve("bin/java"),
            runtimeDir.resolve("lib/modules"),
            appLibDir.resolve("jw365-$cleanVersion.jar")
        )
        if (appLibDir.listFiles()?.none {
                it.isFile && it.name.startsWith("javafx-web-") && it.name.endsWith(".jar")
            } != false) {
            required += appLibDir.resolve("javafx-web-*.jar")
        }
        val missing = required.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Flatpak build artifacts are missing: " +
                    missing.joinToString(", ") { it.relativeTo(projectDir).path }
            )
        }
    }
}


tasks.register<Exec>("flatpakBundle") {
    group = "distribution"
    description = "Creates the distributable Flatpak bundle"
    dependsOn("flatpakBuild")
    doFirst { flatpakBundleFile.get().asFile.parentFile.mkdirs() }
    commandLine(
        "flatpak",
        "build-bundle",
        flatpakRepoDir.get().asFile.absolutePath,
        flatpakBundleFile.get().asFile.absolutePath,
        "io.github.alaurie.JW365"
    )
}

tasks.register<Exec>("createRuntimeImage") {
    group = "distribution"
    description = "Creates the minimized Java runtime image used by application packages"
    dependsOn("jar")
    val runtimeDir = layout.buildDirectory.dir("runtime").get().asFile
    outputs.dir(runtimeDir)

    doFirst {
        if (runtimeDir.exists()) {
            runtimeDir.deleteRecursively()
        }
    }

    commandLine(
        "$javaHome/bin/jlink",
        "--add-modules", "java.base,java.desktop,java.net.http,java.sql,jdk.httpserver,jdk.unsupported,jdk.crypto.ec,jdk.jsobject,jdk.xml.dom",
        "--strip-debug",
        "--no-man-pages",
        "--no-header-files",
        "--compress=zip-6",
        "--output", runtimeDir.absolutePath
    )
}

tasks.register<Exec>("packageDeb") {
    group = "distribution"
    description = "Builds the native Debian package"
    dependsOn("installDist", "createRuntimeImage")
    val distDir = layout.buildDirectory.dir("distributions").get().asFile
    val runtimeDir = layout.buildDirectory.dir("runtime").get().asFile

    doFirst { distDir.mkdirs() }

    commandLine(buildList {
        addAll(listOf(
            "$javaHome/bin/jpackage",
            "--type", "deb",
            "--dest", distDir.absolutePath,
            "--name", "jw365",
            "--app-version", cleanVersion,
            "--vendor", "Alex Laurie",
            "--description", "Modern Linux Client for Windows 365 and Azure Virtual Desktop",
            "--icon", iconFile.absolutePath,
            "--resource-dir", resourceDir.absolutePath,
            "--runtime-image", runtimeDir.absolutePath,
            "--input", inputDir.get().asFile.absolutePath,
            "--main-jar", "jw365-$cleanVersion.jar",
            "--main-class", "org.alaurie.jw365.gui.Jw365Main",
            "--linux-package-name", "jw365",
            "--linux-app-category", "Network",
            "--linux-shortcut",
            "--linux-menu-group", "Network;",
            "--linux-package-deps", "freerdp3-sdl | freerdp3-x11 | freerdp3-wayland"
        ))
        addAll(jpackageJvmOptions)
    })
}

tasks.register<Exec>("packageAppImage") {
    group = "distribution"
    description = "Builds the Linux application image"
    dependsOn("installDist", "createRuntimeImage")
    val distDir = layout.buildDirectory.dir("distributions").get().asFile
    val runtimeDir = layout.buildDirectory.dir("runtime").get().asFile

    doFirst {
        val appImageDir = file("${distDir.absolutePath}/jw365")
        if (appImageDir.exists()) appImageDir.deleteRecursively()
        distDir.mkdirs()
    }

    commandLine(buildList {
        addAll(listOf(
            "$javaHome/bin/jpackage",
            "--type", "app-image",
            "--dest", distDir.absolutePath,
            "--name", "jw365",
            "--app-version", cleanVersion,
            "--vendor", "Alex Laurie",
            "--icon", iconFile.absolutePath,
            "--resource-dir", resourceDir.absolutePath,
            "--runtime-image", runtimeDir.absolutePath,
            "--input", inputDir.get().asFile.absolutePath,
            "--main-jar", "jw365-$cleanVersion.jar",
            "--main-class", "org.alaurie.jw365.gui.Jw365Main"
        ))
        addAll(jpackageJvmOptions)
    })
}

tasks.register<Exec>("packagePortableTar") {
    group = "distribution"
    description = "Builds the portable Linux tarball"
    dependsOn("packageAppImage")
    val distDir = layout.buildDirectory.dir("distributions").get().asFile
    val tarFile = File(distDir, "jw365-$cleanVersion-linux-x64.tar.gz")
    val appDir = File(distDir, "jw365")

    doFirst {
        copy {
            from("src/main/resources/org/alaurie/jw365/gui/icon.png")
            into(appDir)
            rename("icon.png", "jw365.png")
        }
        copy {
            from("packaging/install-desktop.sh")
            into(appDir)
        }
        file("${appDir.absolutePath}/bin/jw365").setExecutable(true)
        file("${appDir.absolutePath}/install-desktop.sh").setExecutable(true)
    }

    workingDir(distDir)
    commandLine("tar", "-czf", tarFile.name, "jw365")
}

tasks.register("deb") {
    group = "distribution"
    description = "Builds the native Debian package (build/distributions/jw365_<version>_amd64.deb)"
    dependsOn("packageDeb")
}

tasks.register<Exec>("packageRpm") {
    group = "distribution"
    description = "Builds the native RPM package"
    dependsOn("installDist", "createRuntimeImage")
    val distDir = layout.buildDirectory.dir("distributions").get().asFile
    val runtimeDir = layout.buildDirectory.dir("runtime").get().asFile

    doFirst {
        val hasRpmBuild = File("/usr/bin/rpmbuild").exists() || File("/bin/rpmbuild").exists()
        if (!hasRpmBuild) throw GradleException(
            "Cannot build RPM package: 'rpmbuild' is not installed.\n" +
            "To install on Debian/Ubuntu: sudo apt install rpm\n" +
            "To install on Fedora/RHEL: sudo dnf install rpm-build"
        )
        distDir.mkdirs()
    }

    commandLine(buildList {
        addAll(listOf(
            "$javaHome/bin/jpackage",
            "--type", "rpm",
            "--dest", distDir.absolutePath,
            "--name", "jw365",
            "--app-version", cleanVersion,
            "--vendor", "Alex Laurie",
            "--description", "Modern Linux Client for Windows 365 and Azure Virtual Desktop",
            "--icon", iconFile.absolutePath,
            "--resource-dir", resourceDir.absolutePath,
            "--runtime-image", runtimeDir.absolutePath,
            "--input", inputDir.get().asFile.absolutePath,
            "--main-jar", "jw365-$cleanVersion.jar",
            "--main-class", "org.alaurie.jw365.gui.Jw365Main",
            "--linux-package-name", "jw365",
            "--linux-app-category", "Network",
            "--linux-shortcut",
            "--linux-menu-group", "Network;",
            "--linux-package-deps", "freerdp"
        ))
        addAll(jpackageJvmOptions)
    })
}

tasks.register("rpm") {
    group = "distribution"
    description = "Builds the native RPM package (build/distributions/jw365-<version>-1.x86_64.rpm)"
    dependsOn("packageRpm")
}

tasks.register("portable") {
    group = "distribution"
    description = "Builds the portable standalone tarball (build/distributions/jw365-<version>-linux-x64.tar.gz)"
    dependsOn("packagePortableTar")
}

tasks.register("packageAll") {
    group = "distribution"
    description = "Builds both the native Debian package and portable standalone tarball"
    dependsOn("deb", "portable")
    if (File("/usr/bin/rpmbuild").exists() || File("/bin/rpmbuild").exists()) {
        dependsOn("rpm")
    }
}
