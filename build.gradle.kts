import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
	alias(libs.plugins.loom)
	checkstyle
}

group = providers.gradleProperty("maven_group").get()

loom {
	splitEnvironmentSourceSets()

	mods {
		register("deepcharter") {
			sourceSet(sourceSets.main.get())
			sourceSet(sourceSets["client"])
		}
	}

	runs {
		// Play-test client. Its own run dir, so the mcpfabric jar that tools/play.sh
		// places in run/play/mods is loaded here and nowhere else (audit condition 1).
		@Suppress("DEPRECATION")
		create("play") {
			client()
			runDir("run/play")
			programArgs("--quickPlaySingleplayer", "deepcharter-playtest", "--width", "854", "--height", "480")
		}
		// Throwaway dedicated server that tools/play.sh uses once to generate the
		// test world template (quick play cannot create a missing world).
		@Suppress("DEPRECATION")
		create("playWorld") {
			server()
			runDir("run/play-world")
		}
	}
}

// Applied scripts compile without the Loom plugin's types, so the Loom runs are offered to gradle/run-jvm.gradle.kts as plain functions.
val addVmArgToRuns: (String) -> Unit = { arg -> loom.runs.configureEach { vmArg(arg) } }
val addVmArgToRun: (String, String) -> Unit = { run, arg ->
	loom.runs.named(run) { vmArg(arg) }
}
val runVmArgs: () -> Map<String, List<String>> = { loom.runs.associate { it.name to it.vmArgs.toList() } }
extra["addVmArgToRuns"] = addVmArgToRuns
extra["addVmArgToRun"] = addVmArgToRun
extra["runVmArgs"] = runVmArgs

fabricApi {
	configureTests {
		createSourceSet = true
		modId = "deepcharter-test"
		enableGameTests = true
		enableClientGameTests = true
		eula = true
	}
}

repositories {
	// Only GeckoLib comes from here, and only this repository may serve it (audit condition 3).
	exclusiveContent {
		forRepository {
			maven {
				name = "GeckoLib"
				url = uri("https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/")
			}
		}
		filter {
			includeGroup("com.geckolib")
		}
	}
}

dependencies {
	minecraft(libs.minecraft)
	implementation(libs.fabric.loader)

	implementation(libs.fabric.api)

	// Pod models (ADR 0030). The jar is nested in ours, so a player installs nothing extra (audit condition 4).
	implementation(libs.geckolib)
	include(libs.geckolib)
}

// Audit condition 2: the resolved GeckoLib jar must be the audited one. Gradle has no dependency verification here, so a
// changed jar (a re-upload, a swapped repository) fails the build before it is packed into ours.
abstract class CheckGeckoLib : DefaultTask() {
	@get:InputFiles
	abstract val jars: ConfigurableFileCollection

	@get:Input
	abstract val sha512: Property<String>

	@TaskAction
	fun check() {
		val found = jars.files.filter { it.name.startsWith("geckolib-fabric-") }
		if (found.size != 1) {
			throw GradleException("expected exactly one GeckoLib jar on the runtime classpath, found ${found.map { it.name }}")
		}
		val digest = MessageDigest.getInstance("SHA-512").digest(found[0].readBytes()).joinToString("") { "%02x".format(it) }
		if (digest != sha512.get()) {
			throw GradleException(
				"${found[0].name} has sha512 $digest, but gradle/libs.versions.toml pins ${sha512.get()}. " +
					"A new GeckoLib is a new audit (docs/tooling/geckolib-audit.md): repeat its hash and source checks, then update the pin.",
			)
		}
	}
}

val checkGeckoLib = tasks.register<CheckGeckoLib>("checkGeckoLib") {
	group = "verification"
	description = "Fails unless the resolved GeckoLib jar is the audited one."
	jars.from(configurations.runtimeClasspath)
	sha512 = libs.versions.geckolibSha512
}
tasks.named("jar") { dependsOn(checkGeckoLib) }

// The same check on the copy that ships: the jar nested in our mod jar must be the one jar there, with the pinned sha512 (audit
// condition 4: Loom's include must not add a second jar or a different one).
abstract class CheckNestedGeckoLib : DefaultTask() {
	@get:InputFile
	abstract val modJar: RegularFileProperty

	@get:Input
	abstract val sha512: Property<String>

	@TaskAction
	fun check() {
		val jarFile = modJar.get().asFile
		ZipFile(jarFile).use { zip ->
			val nested = zip.entries().toList().filter { it.name.startsWith("META-INF/jars/") && !it.isDirectory }
			if (nested.size != 1 || !nested[0].name.startsWith("META-INF/jars/geckolib-fabric-")) {
				throw GradleException("${jarFile.name} must nest exactly one jar, GeckoLib's; it nests ${nested.map { it.name }}")
			}
			val digest = MessageDigest.getInstance("SHA-512").digest(zip.getInputStream(nested[0]).readBytes()).joinToString("") { "%02x".format(it) }
			if (digest != sha512.get()) {
				throw GradleException("${nested[0].name} inside ${jarFile.name} has sha512 $digest, but gradle/libs.versions.toml pins ${sha512.get()}")
			}
		}
	}
}

val checkNestedGeckoLib = tasks.register<CheckNestedGeckoLib>("checkNestedGeckoLib") {
	group = "verification"
	description = "Fails unless the jar nested in our mod jar is the audited GeckoLib."
	modJar = tasks.jar.flatMap { it.archiveFile }
	sha512 = libs.versions.geckolibSha512
}
tasks.check { dependsOn(checkGeckoLib, checkNestedGeckoLib) }

apply(from = "gradle/lang.gradle.kts")
apply(from = "gradle/literals.gradle.kts")
apply(from = "gradle/entity-query-gate.gradle.kts")
apply(from = "gradle/gametest.gradle.kts")
apply(from = "gradle/colour-gate.gradle.kts")
apply(from = "gradle/test-only-gate.gradle.kts")
apply(from = "gradle/clientlock.gradle.kts")
apply(from = "gradle/run-jvm.gradle.kts")

tasks.processResources {
	val modVersion = project.version.toString()
	inputs.property("version", modVersion)

	filesMatching("fabric.mod.json") {
		expand("version" to modVersion)
	}
}

checkstyle {
	toolVersion = libs.versions.checkstyle.get()
	configFile = file("config/checkstyle/checkstyle.xml")
	maxWarnings = 0
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}

java {
	withSourcesJar()
}

// Client GameTests, tools/record-evidence.sh and the play-test client (tools/play.sh) use the Vulkan
// backend on macOS: OpenGL hangs in SDL_GL_SwapWindow while the screen is locked (#251, #253). Set
// DEEPCHARTER_GL=1 to keep OpenGL, for example for Iris shader tryouts. Linux and CI are unchanged.
if (System.getProperty("os.name").lowercase().contains("mac") && System.getenv("DEEPCHARTER_GL") != "1") {
	@Suppress("DEPRECATION")
	loom.runs.matching { it.name in listOf("clientGameTest", "play") }.configureEach {
		programArgs("--graphicsBackend", "vulkan")
	}
}
