import java.io.File

plugins {
    java
    // Bundles the plugin (and the nether-pathfinder native dependency it needs) into a single jar
    id("com.gradleup.shadow") version "9.4.1"
}

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("plugin_version").get()
val mc = providers.gradleProperty("mc").get()
val pluginId = providers.gradleProperty("plugin_id").get()
val pluginName = providers.gradleProperty("plugin_name").get()

// The Java version your plugin's bytecode targets.
// Must be <= the Java version ZenithProxy runs on (the `java` release channel runs Java 21+).
val javaReleaseVersion = 21

java {
    toolchain {
        // The JDK used to *compile*. Must be >= the JDK ZenithProxy was built with (25)
        // because compilation runs ZenithProxy's bundled annotation processor.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    // Native nether terrain-gen + A* (the same library Baritone's elytra / AquariusProxy's NetherRouter use)
    maven("https://babbaj.github.io/maven/") {
        content { includeGroup("dev.babbaj") }
    }
}

// --- ZenithProxy API jar ------------------------------------------------------
// Plugins compile against the ZenithProxy "fat" jar — the exact jar the launcher runs. It bundles the
// entire API plus the @Plugin annotation processor that emits your plugin's metadata file
// (zenithproxy.plugin.json). No Maven publishing needed.
//
// NOTE: until rfresh2/ZenithProxy#313 (BotPrePhysicsTick + Bot#setFallFlying) is merged upstream, the jar
// this must point at is one built from the aquariusnetwork9/ZenithProxy fork's feature/pre-physics-tick-hook
// branch, NOT an official release. See .github/workflows/build.yml for how CI produces it.
val zenithJarPath: String = providers.gradleProperty("zenith_jar")
    .orElse(layout.projectDirectory.file("libs/ZenithProxy.jar").asFile.absolutePath)
    .get()
val zenithJar = files(zenithJarPath)

dependencies {
    // `compileOnly` + `annotationProcessor`: the proxy provides these classes at runtime,
    // and running it as an annotation processor generates your plugin's metadata file.
    compileOnly(zenithJar)
    annotationProcessor(zenithJar)

    // Real runtime dependency (not provided by ZenithProxy core) — shaded into the plugin jar by Shadow.
    // Ships native binaries for linux/windows/macos.
    implementation("dev.babbaj:nether-pathfinder:1.6")
}

// Fail early with a helpful message if the API jar is missing
val checkZenithJar = tasks.register("checkZenithJar") {
    val path = zenithJarPath
    doFirst {
        if (!File(path).exists()) {
            throw GradleException(
                "\nZenithProxy API jar not found at:\n  $path\n\n" +
                "Place a ZenithProxy fat jar at libs/ZenithProxy.jar, or point the build at one with:\n" +
                "  -Pzenith_jar=/path/to/ZenithProxy.jar\n\n" +
                "Until rfresh2/ZenithProxy#313 merges, this MUST be built from the\n" +
                "aquariusnetwork9/ZenithProxy fork, branch feature/pre-physics-tick-hook — an official\n" +
                "release jar will NOT compile (missing BotPrePhysicsTick / Bot#setFallFlying).\n"
            )
        }
    }
}

// --- BuildConstants templating ------------------------------------------------
val templateProps = mapOf(
    "version" to version.toString(),
    "mc_version" to mc,
    "plugin_id" to pluginId,
    "maven_group" to group.toString(),
)
val generateTemplates = tasks.register<Copy>("generateTemplates") {
    inputs.properties(templateProps)
    from("src/main/templates")
    into(layout.buildDirectory.dir("generated/sources/templates/java/main"))
    expand(templateProps)
    filteringCharset = "UTF-8"
}

sourceSets.named("main") {
    java.srcDir(generateTemplates.map { it.destinationDir })
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(checkZenithJar)
    options.encoding = "UTF-8"
    options.release = javaReleaseVersion
}

// --- Packaging ----------------------------------------------------------------
tasks.named<Jar>("jar") {
    enabled = false // the shadow jar is the plugin artifact
}

tasks.named<Jar>("shadowJar") {
    archiveBaseName = pluginName
    archiveClassifier = ""
}

tasks.named("build") {
    dependsOn("shadowJar")
}

// --- Local testing ------------------------------------------------------------
val pluginJar = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }

val installPluginToRun = tasks.register<Copy>("installPluginToRun") {
    from(pluginJar)
    into(layout.projectDirectory.dir("run/plugins"))
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run ZenithProxy with this plugin loaded (local testing)"
    dependsOn(installPluginToRun)
    notCompatibleWithConfigurationCache("Interactive proxy console reads System.in")
    classpath = zenithJar
    mainClass = "com.zenith.Proxy"
    workingDir = layout.projectDirectory.dir("run").asFile
    standardInput = System.`in`
    jvmArgs = listOf(
        "-Xmx300m",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
    )
    outputs.upToDateWhen { false }
}
