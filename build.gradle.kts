import java.security.MessageDigest
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import java.util.zip.ZipFile

// Plugin versions are locked too. Regenerate: see the deepcharter-conventions skill.
buildscript {
	dependencyLocking {
		lockAllConfigurations()
		lockMode = LockMode.STRICT
	}
}

plugins {
	alias(libs.plugins.loom)
	alias(libs.plugins.dependency.analysis)
	alias(libs.plugins.spotless)
	alias(libs.plugins.errorprone)
	checkstyle
}

java {
	// Runtime and test classpaths resolve to the versions the compile classpath chose, so what we compile against is what runs.
	consistentResolution { useCompileClasspathVersions() }
}

dependencyLocking {
	lockAllConfigurations()
	lockMode = LockMode.STRICT
	// Mojang's manifest rules make these OS-specific (macOS: Objective-C bridge, kqueue, Vulkan; Linux: epoll), so a lock
	// written on one system fails on the other. Their checksums stay verified: the metadata holds both systems' artifacts.
	ignoredDependencies.addAll(
		"ca.weblite:java-objc-bridge",
		"io.netty:netty-transport-native-kqueue",
		"io.netty:netty-transport-native-epoll",
		"org.lwjgl:lwjgl-vulkan",
	)
}

// gradle/verification-metadata.xml trusts net.minecraft:minecraft-{merged,common,clientOnly}-*: Loom generates those jars per
// machine with differing bytes, and checks the Mojang jars they come from against Mojang's manifest. All else is sha256-verified.

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

// Stands in for FAIL_ON_PROJECT_REPOS (see settings.gradle.kts): only Loom's repositories and GeckoLib's may exist.
val allowedRepositoryUrls = setOf(
	"https://maven.fabricmc.net/",
	"https://libraries.minecraft.net/",
	"https://repo.maven.apache.org/maven2/",
	"https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/",
)
gradle.projectsEvaluated {
	val unexpected = repositories.filter { repo ->
		!repo.name.startsWith("LoomLocal") && !repo.name.startsWith("LoomGlobal") &&
			(repo as? MavenArtifactRepository)?.url?.toString() !in allowedRepositoryUrls
	}
	check(unexpected.isEmpty()) {
		"Project repositories outside Loom's own and GeckoLib's: ${unexpected.map { it.name }}. " +
			"Declare dependencies from Fabric's, Mojang's or Maven Central only, or audit the new repository and list it here."
	}
}

// The Fabric API modules each source set compiles against, as buildHealth (dependency-analysis) reports them.
// api: the module's types appear in our public signatures. A source set lists every module its own code uses; the client and
// gametest sets do not inherit main's declarations for this check. Code that uses a new module fails buildHealth and names it;
// add it here.
val fabricApiModules = mapOf(
	"api" to listOf("fabric-api-base", "fabric-data-attachment-api-v1", "fabric-menu-api-v1"),
	"implementation" to listOf(
		"fabric-command-api-v2", "fabric-entity-events-v1", "fabric-events-interaction-v0", "fabric-lifecycle-events-v1",
		"fabric-networking-api-v1", "fabric-object-builder-api-v1", "fabric-particles-v1", "fabric-registry-sync-v0",
		"fabric-resource-loader-v1",
	),
	"clientImplementation" to listOf(
		"fabric-api-base", "fabric-data-attachment-api-v1", "fabric-events-interaction-v0", "fabric-key-mapping-api-v1",
		"fabric-lifecycle-events-v1", "fabric-menu-api-v1", "fabric-model-loading-api-v1", "fabric-networking-api-v1",
		"fabric-particles-v1", "fabric-renderer-api-v1", "fabric-rendering-v1", "fabric-resource-loader-v1",
	),
	"gametestImplementation" to listOf(
		"fabric-api-base", "fabric-client-gametest-api-v1", "fabric-data-attachment-api-v1", "fabric-events-interaction-v0",
		"fabric-gametest-api-v1", "fabric-lifecycle-events-v1", "fabric-menu-api-v1", "fabric-networking-api-v1",
		"fabric-rendering-v1", "fabric-resource-loader-v1", "fabric-screen-api-v1",
	),
)

dependencies {
	minecraft(libs.minecraft)
	// api: entrypoints implement ModInitializer, a public Loader type.
	api(libs.fabric.loader)

	// Loom's interface injection makes vanilla classes implement Fabric API interfaces (FabricItem, FabricBlock, FabricBlockState,
	// RenderDataBlockEntity, ...), so javac must see the whole of Fabric API on the compile classpath even where no source names a
	// module. The kind's runtimeOnly fails to compile here; the unused-dependency finding is excluded below instead.
	implementation(libs.fabric.api)
	val fabricApiVersion = libs.versions.fabricApi.get()
	fabricApiModules.forEach { (configuration, modules) ->
		modules.forEach { module -> add(configuration, fabricApi.module(module, fabricApiVersion)) }
	}

	// Pod models (ADR 0030). The jar is nested in ours, so a player installs nothing extra (audit condition 4).
	// Only the client and gametest code touch GeckoLib's types; main needs it at run time.
	runtimeOnly(libs.geckolib)
	"clientImplementation"(libs.geckolib)
	"gametestImplementation"(libs.geckolib)
	include(libs.geckolib)

	errorprone(libs.errorprone.core)
	errorprone(libs.nullaway)
	// @NullMarked and @Nullable are source-retention annotations: not needed at run time.
	compileOnly(libs.jspecify)
	"clientCompileOnly"(libs.jspecify)
	"gametestCompileOnly"(libs.jspecify)
	// @FormatMethod on the gametest failure helpers, so Error Prone checks their format strings
	"gametestCompileOnly"(libs.errorprone.annotations)
}

// Audit condition 2: the resolved GeckoLib jar must be the audited one. Regenerating the verification metadata accepts whatever
// jar resolves; this sha512 changes only with a repeated audit, so a swapped jar fails the build before it is packed into ours.
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

// palantir-java-format owns Java formatting (run `just fmt`); spotlessCheck is part of check. tooling/ is third-party code and is not
// ours to reformat. The two single-file programs outside src/ are Java too. The gate fixtures in tools/tests are strings inside
// Python, not files, so no target here touches them.
spotless {
	java {
		target("src/**/*.java", "gradle/OomProbe.java", "tools/TestOnlyGate.java")
		palantirJavaFormat(libs.versions.palantirJavaFormat.get())
		removeUnusedImports()
	}
}

// RELAXED: Error Prone's unused checks would flag every injector method and its Mixin-supplied parameters; any other unused code is still reported.
val mixinHandlers = listOf("Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant")
	.joinToString(",") { "org.spongepowered.asm.mixin.injection.$it" }

// Error Prone runs on every source set; -Werror fails on any warning.
// RELAXED -processing: javac warns about annotations no processor claims.
// RELAXED -classfile: third-party class files warn per class (Gson's @InlineMe needs error_prone_annotations; JOML's type annotations predate the class-file version javac reads them from).
tasks.withType<JavaCompile>().configureEach {
	options.release = 25
	options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-classfile", "-Werror"))
	options.errorprone {
		disableWarningsInGeneratedCode = true
		// RELAXED: Readable and Unreadable are the domain's own pair (Versioned, CONTEXT.md)
		disable("AvoidCommonTypeNames")
		// RELAXED: vanilla compares Entity, Level, Item, Block and registry objects with ==, and our constant records (Chassis, TerminalType, ScanSlice.Cell) only ever exist as the constants
		disable("ReferenceEquality")
		// RELAXED: enum order is the contract of stream codecs, theme data indices and seeded hashes, so ordinal() is the stable key there
		disable("EnumOrdinal")
		// RELAXED: enums hold Minecraft types (SoundEvent, Identifier, MapCodec) that cannot carry @Immutable
		disable("ImmutableEnumChecker")
		// Only @NullMarked packages are checked: a feature package opts in with a package-info.java (deepcharter-conventions)
		check("NullAway", CheckSeverity.ERROR)
		option("NullAway:OnlyNullMarked", "true")
		option("NullAway:JSpecifyMode", "true")
		// RELAXED: Mixin fills a @Shadow field, so NullAway would report it uninitialized
		option("NullAway:ExcludedFieldAnnotations", "org.spongepowered.asm.mixin.Shadow")
		option("UnusedMethod:ExemptingMethodAnnotations", mixinHandlers)
		option("Unused:methodAnnotationsExemptingParameters", mixinHandlers)
	}
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

// REPLACES: JUnit/AssertJ/JaCoCo — no src/test; tests are GameTests in Loom run JVMs, which JaCoCo cannot see, and an unused JUnit dependency fails buildHealth.
dependencyAnalysis {
	issues {
		all {
			onAny { severity("fail") }
			// The fabric-api umbrella is on the compile classpath for Loom's interface injection (see dependencies {}), but no source names it.
			onUnusedDependencies { exclude("net.fabricmc.fabric-api:fabric-api") }
			// Libraries that com.mojang:minecraft's metadata supplies and its release pins, and Loom's merged Minecraft artifact
			// (its name carries a hash). Declaring them would add a second source of truth for versions that move with Minecraft.
			onUsedTransitiveDependencies {
				exclude("com.mojang:brigadier", "com.mojang:datafixerupper", "io.netty:netty-buffer", "org.slf4j:slf4j-api")
				excludeRegex("net\\.minecraft:minecraft-common-.*")
			}
		}
	}
}
// check already runs runGameTest (Fabric API wires it in), the checkstyle tasks and the gates above; buildHealth is not part of check by default.
tasks.check { dependsOn(tasks.named("buildHealth")) }
