import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

plugins {
    id("zenithproxy.plugin.dev") version "1.2.+"
}

group = property("maven_group") as String
version = property("plugin_version") as String
val mc = property("mc") as String
val pluginId = property("plugin_id") as String
val zenithVersion = property("zenith_version") as String
val gsonVersion = property("gson_version") as String

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

zenithProxyPlugin {
    buildConstants {
        fields = mapOf(
            "VERSION" to project.version.toString(),
            "MC_VERSION" to mc,
            "PLUGIN_ID" to pluginId,
            "MAVEN_GROUP" to project.group.toString(),
        )
    }
    javaReleaseVersion = JavaLanguageVersion.of(21)
    runTaskMixinLauncher = true
}

repositories {
    maven("https://maven.2b2t.vc/releases") {
        description = "ZenithProxy Releases"
    }
    maven("https://maven.2b2t.vc/remote") {
        description = "Dependencies used by ZenithProxy"
    }
    mavenCentral()
}

dependencies {
    zenithProxy("com.zenith:ZenithProxy:$zenithVersion+$mc")
    // Gson comes from ZenithProxy at run time (2.14.0 in 3.7.2, the version the compile classpath already resolves).
    compileOnly("com.google.code.gson:gson:$gsonVersion")
    testImplementation("com.google.code.gson:gson:$gsonVersion")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/**
 * Writes io/github/digitalisp/mapart/build-info.properties: plugin version, git commit (short hash, "-dirty" when tracked files
 * differ from it) and build time, so the plugin can log and show which build runs (BuildInfo). Runs on every build,
 * at execution time (configuration-cache safe). Only the jars get the file: the tests' inputs stay unchanged.
 */
abstract class BuildInfoTask : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Input
    abstract val pluginVersion: Property<String>

    @get:Internal
    abstract val repoDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val commit = git("rev-parse", "--short=7", "HEAD") ?: "unknown"
        val dirty = git("status", "--porcelain", "--untracked-files=no")?.isNotBlank() ?: false
        val built = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val file = outputDir.file("io/github/digitalisp/mapart/build-info.properties").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            "version=${pluginVersion.get()}\ncommit=$commit${if (dirty) "-dirty" else ""}\nbuilt=$built\n"
        )
        logger.lifecycle("Build info: ${pluginVersion.get()} ($commit${if (dirty) "-dirty" else ""}), built $built")
    }

    private fun git(vararg args: String): String? = try {
        val out = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine(listOf("git") + args)
            workingDir = repoDir.get().asFile
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        if (result.exitValue == 0) out.toString().trim() else null
    } catch (e: Exception) {
        null
    }
}

val buildInfo = tasks.register<BuildInfoTask>("buildInfo") {
    pluginVersion.set(project.version.toString())
    repoDir.set(layout.projectDirectory)
    outputDir.set(layout.buildDirectory.dir("generated/build-info"))
    outputs.upToDateWhen { false }
}

tasks {
    test {
        useJUnitPlatform()
    }
    withType<Jar>().configureEach {
        from(buildInfo)
    }
}
