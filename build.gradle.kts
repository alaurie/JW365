plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

val rawVersion: String = (project.findProperty("appVersion") as? String)
    ?: System.getenv("APP_VERSION")
    ?: "1.0.0"

val cleanVersion: String = rawVersion.removePrefix("v").trim()

group = "org.alaurie"
version = cleanVersion

repositories {
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
    version = "25.0.2"
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.web")
}

application {
    mainClass.set("org.alaurie.jw365.gui.Jw365Main")
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Xms24m",
        "-Xmx192m",
        "-XX:ReservedCodeCacheSize=64m",
        "-XX:CICompilerCount=2",
        "-XX:+UseSerialGC",
        "-XX:MinHeapFreeRatio=10",
        "-XX:MaxHeapFreeRatio=20"
    )
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    jvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Xms24m",
        "-Xmx192m",
        "-XX:ReservedCodeCacheSize=64m",
        "-XX:CICompilerCount=2",
        "-XX:+UseSerialGC",
        "-XX:MinHeapFreeRatio=10",
        "-XX:MaxHeapFreeRatio=20"
    )
}
// --------------------------------------------------------------------------
// Packaging: jlink minimal runtime + jpackage .deb, .rpm & portable tarball
// --------------------------------------------------------------------------

val javaHome = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile.absolutePath

tasks.register<Exec>("createRuntimeImage") {
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
    dependsOn("installDist", "createRuntimeImage")
    val distDir = layout.buildDirectory.dir("distributions").get().asFile
    val runtimeDir = layout.buildDirectory.dir("runtime").get().asFile
    val inputDir = layout.buildDirectory.dir("install/jw365/lib").get().asFile
    val iconFile = file("src/main/resources/org/alaurie/jw365/gui/icon.png")
    val resourceDir = file("src/package/resources")

    doFirst {
        distDir.mkdirs()
    }

    commandLine(
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
        "--input", inputDir.absolutePath,
        "--main-jar", "jw365-$cleanVersion.jar",
        "--main-class", "org.alaurie.jw365.gui.Jw365Main",
        "--linux-package-name", "jw365",
        "--linux-app-category", "Network",
        "--linux-shortcut",
        "--linux-menu-group", "Network;",
        "--linux-package-deps", "freerdp3-sdl | freerdp3-x11 | freerdp3-wayland",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "-Xms24m",
        "--java-options", "-Xmx192m",
        "--java-options", "-XX:ReservedCodeCacheSize=64m",
        "--java-options", "-XX:CICompilerCount=2",
        "--java-options", "-XX:+UseSerialGC",
        "--java-options", "-XX:MinHeapFreeRatio=10",
        "--java-options", "-XX:MaxHeapFreeRatio=20"
    )
}

tasks.register<Exec>("packageAppImage") {
    dependsOn("installDist", "createRuntimeImage")
    val distDir = layout.buildDirectory.dir("distributions").get().asFile
    val runtimeDir = layout.buildDirectory.dir("runtime").get().asFile
    val inputDir = layout.buildDirectory.dir("install/jw365/lib").get().asFile
    val iconFile = file("src/main/resources/org/alaurie/jw365/gui/icon.png")
    val resourceDir = file("src/package/resources")

    doFirst {
        val appImageDir = file("${distDir.absolutePath}/jw365")
        if (appImageDir.exists()) {
            appImageDir.deleteRecursively()
        }
        distDir.mkdirs()
    }

    commandLine(
        "$javaHome/bin/jpackage",
        "--type", "app-image",
        "--dest", distDir.absolutePath,
        "--name", "jw365",
        "--app-version", cleanVersion,
        "--vendor", "Alex Laurie",
        "--icon", iconFile.absolutePath,
        "--resource-dir", resourceDir.absolutePath,
        "--runtime-image", runtimeDir.absolutePath,
        "--input", inputDir.absolutePath,
        "--main-jar", "jw365-$cleanVersion.jar",
        "--main-class", "org.alaurie.jw365.gui.Jw365Main",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "-Xms24m",
        "--java-options", "-Xmx192m",
        "--java-options", "-XX:ReservedCodeCacheSize=64m",
        "--java-options", "-XX:CICompilerCount=2",
        "--java-options", "-XX:+UseSerialGC",
        "--java-options", "-XX:MinHeapFreeRatio=10",
        "--java-options", "-XX:MaxHeapFreeRatio=20"
    )
}

tasks.register<Exec>("packagePortableTar") {
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
            from("src/package/resources/install-desktop.sh")
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
    dependsOn("installDist", "createRuntimeImage")
    val distDir = layout.buildDirectory.dir("distributions").get().asFile
    val runtimeDir = layout.buildDirectory.dir("runtime").get().asFile
    val inputDir = layout.buildDirectory.dir("install/jw365/lib").get().asFile
    val iconFile = file("src/main/resources/org/alaurie/jw365/gui/icon.png")
    val resourceDir = file("src/package/resources")

    doFirst {
        val hasRpmBuild = File("/usr/bin/rpmbuild").exists() || File("/bin/rpmbuild").exists()
        if (!hasRpmBuild) {
            throw GradleException(
                "Cannot build RPM package: 'rpmbuild' is not installed.\n" +
                "To install on Debian/Ubuntu: sudo apt install rpm\n" +
                "To install on Fedora/RHEL: sudo dnf install rpm-build"
            )
        }
        distDir.mkdirs()
    }

    commandLine(
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
        "--input", inputDir.absolutePath,
        "--main-jar", "jw365-$cleanVersion.jar",
        "--main-class", "org.alaurie.jw365.gui.Jw365Main",
        "--linux-package-name", "jw365",
        "--linux-app-category", "Network",
        "--linux-shortcut",
        "--linux-menu-group", "Network;",
        "--linux-package-deps", "freerdp",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "-Xms24m",
        "--java-options", "-Xmx192m",
        "--java-options", "-XX:ReservedCodeCacheSize=64m",
        "--java-options", "-XX:CICompilerCount=2",
        "--java-options", "-XX:+UseSerialGC",
        "--java-options", "-XX:MinHeapFreeRatio=10",
        "--java-options", "-XX:MaxHeapFreeRatio=20"
    )
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
