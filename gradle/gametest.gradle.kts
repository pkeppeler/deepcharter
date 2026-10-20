import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.regex.Pattern

// World only: eula/server.properties/mods under build/run/gameTest are Loom's.
val deleteGameTestWorld = tasks.register<Delete>("deleteGameTestWorld") {
	delete(layout.buildDirectory.dir("run/gameTest/world"))
}

tasks.named("runGameTest") { dependsOn(deleteGameTestWorld) }

// fabric.mod.json for the gametest mod is generated, so no PR edits a list to register a test.
// The committed template is src/gametest/fabric.mod.template.json; this task fills its two
// entrypoint placeholders from the classes under src/gametest/java and writes the result where
// processGametestResources picks it up. Classes are sorted by content, not by name:
//   server GameTest    a class with an @GameTest method       -> fabric-gametest
//   client GameTest    implements FabricClientGameTest        -> fabric-client-gametest
//   evidence scenario  extends EvidenceScenario               -> fabric-client-gametest
// Filter: -PclientTests=<name or glob>[,...] keeps only matching client classes, so one client test can run alone (see .claude/skills/deepcharter-conventions/tests.md).
// Order: classes are listed sorted by name. The suite must not depend on that order: -PgametestOrder=reverse
// lists them in reverse name order, to catch a test that leaks world state into a later one.
//
// The scan is GametestScan below, so it holds no state of the script: the tasks that run it (generateGametestModJson,
// checkGametestFixtures) pass in everything it reads.
class GametestFound(val server: List<String>, val client: List<String>)

object GametestScan {
	// A direct getDataStorage().set( (or a getDataStorage() kept in a variable, so a later set is not seen) leaks a world-global record
	// if the code between it and its restore throws. Tests swap through WorldData; only WorldData and a swap marked with the comment
	// below, with a reason, which deliberately spans listener phases within one tick, call it directly. Scratch SavedDataStorage instances are fine.
	private const val DIRECT_SWAP_MARKER = "// world-data:"
	private val directSwap = Regex("""getDataStorage\(\)\s*\.\s*set\s*\(|getDataStorage\(\)\s*[;,)]""")
	// A test or scenario that opens a room in layer rock with a direct air write skips the RoomSeal that keeps worldgen lava out of it
	// (PR 229 found one that flooded). In a file that touches a layer dimension, only RoomCarver.carve, which seals first, writes a
	// room's air. A setBlock, setBlockState, setBlockAndUpdate or box/fill helper call that names Blocks.AIR is flagged; a write that is not layer
	// rock (the surface, one block inside a sealed room) carries the comment below, with its reason, on its statement or the comment line above it.
	// This misses a carve whose air is not spelled Blocks.AIR, one through destroyBlock, removeBlock or a differently named helper,
	// and one in a file that reaches a layer level without LayerChain.dimension(.
	private const val CARVE_MARKER = "// room-carver:"
	private val carveWrite = Regex("""\b(?:setBlock|setBlockState|setBlockAndUpdate|box|fill|fillFloor|fillRock)\s*\([^;]*\bBlocks\.(?:CAVE_)?AIR\b""")
	// A client GameTest, client test support file or evidence scenario (anything under test/evidence) that waits with context.waitFor(pred[, ticks]) or waitForScreen waits on a tick budget
	// (Fabric's default is 200 ticks). The client and server tick unthrottled, so the same budget is a different wall time under load,
	// and the test flakes (issues 262, 277). Wait with ClientWait.until or ClientWait.screen, which poll a wall-clock deadline and name
	// what they awaited and what they saw. A fixed span of ticks that is not a poll uses waitTicks. A waitFor that must stay carries the
	// comment below, with a reason, on its statement or the comment line above it; a bare marker, or one inside a string, does not count.
	private const val TICK_WAIT_MARKER = "// tick-wait:"
	private val tickWaitCall = Regex("""\bwaitFor(?:Screen)?\s*\(""")
	// A server GameTest shares its world with every test beside it, so a clock write moves the sky or gameplay time under them. It goes through
	// TestClocks.paused (restores in a finally on the same tick) or carries the marker below, with a reason, on its statement or the comment line above it.
	// Client tests and evidence scenarios run in their own world and are exempt. TestClocks.java is the one file that writes a clock directly.
	private const val WORLD_CLOCK_MARKER = "// world-clock:"
	private val worldClockWrite = Regex("""\.\s*(?:setTotalTicks|setPaused|addTicks|setRate)\s*\(""")
	// A client test shares one client with every test after it, so a HUD it hides (F1) and never shows again fails the next test that draws on
	// it (PR 330). A HUD toggle (keyToggleGui, hideGui, the 26.3 hud.toggle(), an F1 key code) goes through ClientChecks.withHudHidden or withHudShown,
	// which restore it in a finally, or carries the marker below, with a reason, on its statement or the comment line above it. ClientChecks.java is the one file
	// that toggles directly. The scan matches the names, not the receiver.
	private const val HUD_TOGGLE_MARKER = "// hud-toggle:"
	private val hudToggle = Regex("""\b(?:keyToggleGui|hideGui|KEY_F1)\b|\bhud\s*\.\s*toggle\s*\(""")
	// FarChunks counts which test holds each forced chunk and lets it go when the test finishes (#569), so a chunk a test forces and never
	// releases cannot pin its generation neighbourhood (up to 17 x 17 chunks) for the rest of the run. A raw setChunkForced, true or false, skips
	// that count (a stale hold un-forces a chunk another test re-forced). Any setChunkForced or updateChunkForced call or method reference, and
	// any addTicket* of TicketType.FORCED, outside FarChunks.java is flagged, or marked with the comment below, with a reason, on its statement
	// or the comment line above it.
	private const val FORCE_MARKER = "// force-ok:"
	private val chunkForce = Regex("""\b(?:set|update)ChunkForced\s*\(|::\s*(?:set|update)ChunkForced\b|\baddTicket\w*\s*\(\s*TicketType\s*\.\s*FORCED\b""")
	// GameTestHelper.onEachTick queues one runnable per remaining tick of the test's maxTicks, and the runner keeps every finished test until
	// the run ends, so the suite's live heap grew to 4.4 GB (#548). TestTicks.each keeps one. No suppression: there is no case for the queue.
	private val onEachTickCall = Regex("""\bonEachTick\b""")

	private val literalsAndComments = Regex("(?s)\"\"\"(?:\\\\.|.)*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|/\\*.*?\\*/|//[^\\n]*")
	private val notNewline = Regex("[^\\n]")
	private val clientImplements = Regex("""\bimplements\s+[^{]*\bFabricClientGameTest\b""")
	private val clientSide = Regex("""\bimplements\s+[^{]*\bFabricClientGameTest\b|\bextends\s+EvidenceScenario\b""")
	private val abstractClass = Regex("""\babstract\s+class\s""")
	private val gameTestAnnotation = Regex("""@GameTest\b""")
	private val savedDataType = Regex("""SavedDataType<(\w+)>\s+TYPE\b""")

	// One rule for every marker in this file: `// <name>:` then a reason, on the line with literals blanked (blankLiterals), so a bare
	// marker or one inside a string, text block or char literal never counts. A gate asks hasMarker, never a plain contains.
	private fun hasMarker(rawLine: String, marker: String): Boolean =
		Regex("//\\s*" + Pattern.quote(marker.substring(2).trim()) + "\\s*\\S").containsMatchIn(rawLine)

	private fun lineOf(text: String, start: Int): Int = text.substring(0, start).count { it == '\n' }

	// Where the marker may sit is markedNear's (literals.gradle.kts): the statement's lines or the comment lines above it, so palantir's wrapping cannot hide it.
	private fun marked(markedNear: (List<String>, Int, (String) -> Boolean) -> Boolean, rawLines: List<String>, line: Int, marker: String): Boolean =
		markedNear(rawLines, line) { hasMarker(it, marker) }

	/**
	 * One scan of a source tree: the entrypoint lists, ordered, or a GradleException naming every problem. [mainSources] and
	 * [guardSource] are the files WorldDataGuard must list every saved-data record of the mod in, or it misses a swap of a new one.
	 */
	fun scan(
		javaRoot: File,
		clientFilter: String?,
		order: String,
		mainSources: Collection<File>,
		guardSource: File,
		blankLiterals: (String) -> String,
		markedNear: (List<String>, Int, (String) -> Boolean) -> Boolean,
	): GametestFound {
		val javaFiles = javaRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.toList().sorted()
		val server = mutableListOf<String>()
		var client = mutableListOf<String>()
		val problems = mutableListOf<String>()
		for (source in javaFiles) {
			val className = javaRoot.toPath().relativize(source.toPath()).toString()
				.replace(Regex("\\.java$"), "").replace(File.separator, ".")
			val simpleName = className.substring(className.lastIndexOf(".") + 1)
			val sourceText = source.readText()
			// Code only: comments and string literals (text blocks included) must not match.
			val text = literalsAndComments.replace(sourceText) { match ->
				(if (match.value.startsWith("/")) " " else "\"\"") + match.value.replace(notNewline, "")
			}
			val rawLines = blankLiterals(sourceText).reader().readLines()
			if (source.name != "WorldData.java") {
				for (direct in directSwap.findAll(text)) {
					val line = lineOf(text, direct.range.first)
					if (!marked(markedNear, rawLines, line, DIRECT_SWAP_MARKER)) {
						problems.add("$className:${line + 1} swaps or keeps getDataStorage() directly; swap a world-global record with WorldData.with, WorldData.swap or WorldData.replace, which restore it in a finally, or mark a deliberate listener-phase swap with '$DIRECT_SWAP_MARKER <reason>'")
					}
				}
			}
			if (text.contains("LayerChain.dimension(")) {
				for (carve in carveWrite.findAll(text)) {
					val line = lineOf(text, carve.range.first)
					if (!marked(markedNear, rawLines, line, CARVE_MARKER)) {
						problems.add("$className:${line + 1} writes air in a file that touches a layer dimension; carve a room with RoomCarver.carve, which seals the shell against worldgen lava first, or mark a write that is not layer rock with '$CARVE_MARKER <reason>'")
					}
				}
			}
			if (clientImplements.containsMatchIn(text) || className.contains(".test.support.") || className.contains(".test.evidence.")) {
				for (tickWait in tickWaitCall.findAll(text)) {
					val line = lineOf(text, tickWait.range.first)
					if (!marked(markedNear, rawLines, line, TICK_WAIT_MARKER)) {
						problems.add("$className:${line + 1} waits with waitFor or waitForScreen, a tick budget; poll the wall clock with ClientWait.until or ClientWait.screen, or mark a deliberate fixed-tick wait with '$TICK_WAIT_MARKER <reason>'")
					}
				}
			}
			if (source.name != "ClientChecks.java") {
				for (hud in hudToggle.findAll(text)) {
					val line = lineOf(text, hud.range.first)
					if (!marked(markedNear, rawLines, line, HUD_TOGGLE_MARKER)) {
						problems.add("$className:${line + 1} toggles the HUD directly, which the next client test inherits; hide it with ClientChecks.withHudHidden (or show it with withHudShown), which restore it in a finally, or mark a deliberate toggle with '$HUD_TOGGLE_MARKER <reason>'")
					}
				}
			}
			for (call in onEachTickCall.findAll(text)) {
				problems.add("$className:${lineOf(text, call.range.first) + 1} references onEachTick, which queues one runnable per remaining tick of the test and keeps them all until the run ends; run the body every tick with TestTicks.each(helper, body)")
			}
			val isClientSide = clientSide.containsMatchIn(text)
			if (!isClientSide && source.name != "TestClocks.java") {
				for (clockWrite in worldClockWrite.findAll(text)) {
					val line = lineOf(text, clockWrite.range.first)
					if (!marked(markedNear, rawLines, line, WORLD_CLOCK_MARKER)) {
						problems.add("$className:${line + 1} writes a world clock in a server test, which every test in the world sees; write it through TestClocks.paused, which restores it in a finally on the same tick, or mark a deliberate write with '$WORLD_CLOCK_MARKER <reason>' (the scan matches the method name, not the receiver; if this is not a world clock, mark it '$WORLD_CLOCK_MARKER not a clock')")
					}
				}
			}
			if (source.name != "FarChunks.java") {
				for (force in chunkForce.findAll(text)) {
					val line = lineOf(text, force.range.first)
					if (!marked(markedNear, rawLines, line, FORCE_MARKER)) {
						problems.add("$className:${line + 1} sets a chunk's force directly, which FarChunks cannot count or release when the test ends; use FarChunks.force(helper, level, x, z) and FarChunks.release(helper, level, x, z), or mark a deliberate use with '$FORCE_MARKER <reason>'")
					}
				}
			}
			if (abstractClass.containsMatchIn(text)) {
				continue
			}
			val isServer = gameTestAnnotation.containsMatchIn(text)
			if (isServer && isClientSide) {
				problems.add("$className has both an @GameTest method and is a client GameTest; split it in two")
			} else if (isServer) {
				server.add(className)
			} else if (isClientSide) {
				client.add(className)
			} else if (simpleName.endsWith("Test") || simpleName.endsWith("Scenario")) {
				problems.add("$className is named like a test but has no @GameTest method, does not implement FabricClientGameTest and does not extend EvidenceScenario, so it would not be registered")
			}
		}
		if (server.isEmpty()) {
			problems.add("no server GameTest class (a class with an @GameTest method) found under src/gametest/java")
		}
		if (client.isEmpty()) {
			problems.add("no client GameTest or evidence scenario found under src/gametest/java")
		}
		val guarded = guardSource.readText()
		for (source in mainSources.sorted()) {
			for (match in savedDataType.findAll(source.readText())) {
				val record = match.groupValues[1]
				if (!guarded.contains("$record.TYPE")) {
					problems.add("$record declares a SavedDataType, which ${guardSource.name} does not watch; add $record.TYPE to its WATCHED list")
				}
			}
		}
		if (problems.isNotEmpty()) {
			throw GradleException("gametest entrypoints:\n  " + problems.joinToString("\n  "))
		}

		if (clientFilter != null) {
			val terms = clientFilter.split(",").map { it.trim() }
			if (terms.any { it.isEmpty() }) {
				throw GradleException("-PclientTests is '$clientFilter', expected comma-separated client test class names or globs")
			}
			val kept = mutableSetOf<String>()
			val unmatched = mutableListOf<String>()
			for (term in terms) {
				val regex = Pattern.compile(term.split("*").joinToString(".*") { Pattern.quote(it) })
				val hits = client.filter { regex.matcher(it).matches() || regex.matcher(it.substring(it.lastIndexOf(".") + 1)).matches() }
				if (hits.isEmpty()) {
					unmatched.add(term)
				}
				kept.addAll(hits)
			}
			if (unmatched.isNotEmpty()) {
				throw GradleException("-PclientTests term(s) ${unmatched.joinToString(", ") { "'$it'" }} match no client GameTest or evidence scenario; client classes: ${client.sorted().joinToString(", ") { it.substring(it.lastIndexOf(".") + 1) }}")
			}
			client = client.filter { kept.contains(it) }.toMutableList()
		}

		fun ordered(names: List<String>): List<String> = if (order == "reverse") names.sorted().reversed() else names.sorted()
		return GametestFound(ordered(server), ordered(client))
	}
}

val generateGametestModJson = tasks.register("generateGametestModJson") {
	@Suppress("UNCHECKED_CAST")
	val blankLiterals = project.extra["blankLiterals"] as (String) -> String
	@Suppress("UNCHECKED_CAST")
	val markedNear = project.extra["markedNear"] as (List<String>, Int, (String) -> Boolean) -> Boolean
	val generatedGametestResources = layout.buildDirectory.dir("generated/gametest-resources")
	val template = file("src/gametest/fabric.mod.template.json")
	// WorldDataGuard must list every saved-data record of the mod, or it misses a swap of a new one.
	val mainSources = fileTree("src/main/java") { include("**/*.java") }
	val guardSource = file("src/gametest/java/io/github/pkeppeler/deepcharter/test/support/WorldDataGuard.java")
	// -PgametestJavaRoot points the scan at another tree, so a test can feed it fixtures.
	val javaRoot = file(providers.gradleProperty("gametestJavaRoot").getOrElse("src/gametest/java"))
	val order = providers.gradleProperty("gametestOrder").getOrElse("name")
	if (order !in listOf("name", "reverse")) {
		throw GradleException("-PgametestOrder is '$order', expected name or reverse")
	}
	inputs.property("order", order)
	// An input, so changing the filter regenerates the list.
	val clientFilter = providers.gradleProperty("clientTests").orNull
	inputs.property("clientTests", clientFilter).optional(true)
	val outputFile = generatedGametestResources.map { it.file("fabric.mod.json") }
	val sources = fileTree(javaRoot) { include("**/*.java") }
	inputs.files(sources).withPropertyName("gametestSources")
	inputs.file(template).withPropertyName("template")
	inputs.files(mainSources).withPropertyName("mainSources")
	outputs.file(outputFile)
	doLast {
		val found = GametestScan.scan(javaRoot, clientFilter, order, mainSources.files, guardSource, blankLiterals, markedNear)
		var json = template.readText()
		mapOf("SERVER_TESTS" to found.server, "CLIENT_TESTS" to found.client).forEach { (token, names) ->
			val placeholder = "\"@$token@\""
			if (!json.contains(placeholder)) {
				throw GradleException("$template lacks the $placeholder placeholder")
			}
			json = json.replace(placeholder, names.joinToString(",\n\t\t\t") { "\"$it\"" })
		}
		outputFile.get().asFile.writeText(json)
	}
}

// -PfixtureCases=<json file>: [{"name", "root", "clientTests" (string or null), "gate" (absent, or "entity_query", "record_holder", "null_marked" or "null_marked_roots" to run that gate over the tree instead)}, ...]. Runs the scan over each tree and writes
// -PfixtureResults=<json file>: {name: {"ok", "message", "server", "client"}}. A tree the scan rejects is a result, not a build failure.
tasks.register("checkGametestFixtures") {
	@Suppress("UNCHECKED_CAST")
	val blankLiterals = project.extra["blankLiterals"] as (String) -> String
	@Suppress("UNCHECKED_CAST")
	val markedNear = project.extra["markedNear"] as (List<String>, Int, (String) -> Boolean) -> Boolean
	// The entity-query gate's scan (entity-query-gate.gradle.kts), which checkGametestFixtures runs over fixture trees.
	@Suppress("UNCHECKED_CAST")
	val entityQueryProblems = project.extra["entityQueryProblems"] as (File, File, (String) -> String, (List<String>, Int, (String) -> Boolean) -> Boolean) -> List<String>
	val entityQueryAdvice = project.extra["entityQueryAdvice"] as String
	// The record-holder gate's scan (record-holder-gate.gradle.kts), run the same way.
	@Suppress("UNCHECKED_CAST")
	val recordHolderProblems = project.extra["recordHolderProblems"] as (File, File, (String) -> String, (List<String>, Int, (String) -> Boolean) -> Boolean) -> List<String>
	val recordHolderAdvice = project.extra["recordHolderAdvice"] as String
	// The null-marked gate's scan (null-marked-gate.gradle.kts), run the same way.
	@Suppress("UNCHECKED_CAST")
	val nullMarkedProblems = project.extra["nullMarkedProblems"] as (File, File) -> List<String>
	val nullMarkedAdvice = project.extra["nullMarkedAdvice"] as String
	val projectRoot = projectDir
	val nullMarkedRoots = project.extra["nullMarkedRoots"] as FileCollection
	val mainSources = fileTree("src/main/java") { include("**/*.java") }
	val guardSource = file("src/gametest/java/io/github/pkeppeler/deepcharter/test/support/WorldDataGuard.java")
	val cases = file(providers.gradleProperty("fixtureCases").getOrElse("missing -PfixtureCases"))
	val results = file(providers.gradleProperty("fixtureResults").getOrElse("missing -PfixtureResults"))
	outputs.upToDateWhen { false }
	doLast {
		if (!cases.isFile) {
			throw GradleException("-PfixtureCases and -PfixtureResults are required: $cases")
		}
		val report = linkedMapOf<String, Map<String, Any>>()
		for (c in JsonSlurper().parse(cases) as List<*>) {
			c as Map<*, *>
			val name = c["name"] as String
			try {
				if (c["gate"] == "entity_query") {
					val root = File(c["root"] as String)
					val problems = entityQueryProblems(root, root, blankLiterals, markedNear)
					if (problems.isNotEmpty()) {
						throw GradleException("Entity queries in production code. $entityQueryAdvice\n  " + problems.joinToString("\n  "))
					}
					report[name] = linkedMapOf("ok" to true, "message" to "", "server" to emptyList<String>(), "client" to emptyList<String>())
					continue
				}
				if (c["gate"] == "record_holder") {
					val root = File(c["root"] as String)
					val problems = recordHolderProblems(root, root, blankLiterals, markedNear)
					if (problems.isNotEmpty()) {
						throw GradleException("Value-holder classes that are not records. $recordHolderAdvice\n  " + problems.joinToString("\n  "))
					}
					report[name] = linkedMapOf("ok" to true, "message" to "", "server" to emptyList<String>(), "client" to emptyList<String>())
					continue
				}
				if (c["gate"] == "null_marked_roots") {
					// The roots the real gate scans, as project-relative paths in the message.
					report[name] = linkedMapOf("ok" to true, "message" to nullMarkedRoots.files.filter { it.isDirectory }.sorted().joinToString("\n") { it.relativeTo(projectRoot).path }, "server" to emptyList<String>(), "client" to emptyList<String>())
					continue
				}
				if (c["gate"] == "null_marked") {
					val root = File(c["root"] as String)
					val problems = nullMarkedProblems(root, root)
					if (problems.isNotEmpty()) {
						throw GradleException("Packages NullAway does not check. $nullMarkedAdvice\n  " + problems.joinToString("\n  "))
					}
					report[name] = linkedMapOf("ok" to true, "message" to "", "server" to emptyList<String>(), "client" to emptyList<String>())
					continue
				}
				val found = GametestScan.scan(File(c["root"] as String), c["clientTests"] as String?, "name", mainSources.files, guardSource, blankLiterals, markedNear)
				report[name] = linkedMapOf("ok" to true, "message" to "", "server" to found.server, "client" to found.client)
			} catch (e: GradleException) {
				report[name] = linkedMapOf("ok" to false, "message" to e.message!!, "server" to emptyList<String>(), "client" to emptyList<String>())
			}
		}
		results.writeText(JsonOutput.toJson(report))
	}
}

tasks.named<ProcessResources>("processGametestResources") {
	dependsOn(generateGametestModJson)
	from(layout.buildDirectory.dir("generated/gametest-resources"))
}
