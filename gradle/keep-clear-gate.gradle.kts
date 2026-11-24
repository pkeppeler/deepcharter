// Keep-clear gate (#1030): a client file that adds, attaches or replaces a HUD element must also register what it draws with
// HudKeepClear (client/ui), the list a popup places itself by, or say why it draws nothing to keep clear of. This fails the build on a
// file in src/client/java that calls HudElementRegistry.addFirst, addLast, attachElementAfter or replaceElement, has no HudKeepClear.register
// call, and has a call line without `// keep-clear-ok: <reason>` on it or the contiguous `//` lines above it.
//
// The scan is source text, as the other gates are. It is per file, not per element: a file that registers a rectangle is taken to
// register every element it draws; a reviewer reads the rectangle against the draw code, and the client tests
// (StepToastClearOfHudClientTest, and StepToastClearOfSonarClientTest for the navigator's scope) draw the real HUD and check that
// the popup covers none of its text. Comments are blanked before the match, so a mention in prose
// neither makes a call nor registers one.
//
// tools/tests/keep_clear_gate_check.py runs the scan over fixture trees (checkGametestFixtures in gametest.gradle.kts).
object KeepClearGate {
	private val CALL = Regex("""\bHudElementRegistry\s*\.\s*(addFirst|addLast|attachElementAfter|replaceElement)\s*\(""")
	private val REGISTER = Regex("""\bHudKeepClear\s*\.\s*register\s*\(""")
	private val MARKER = Regex("""//\s*keep-clear-ok:\s*\S""")

	/** {@code text} with every comment turned into spaces, newlines kept, so offsets and line numbers still line up. */
	private fun withoutComments(text: String): String {
		val out = StringBuilder(text)
		var i = 0
		while (i < text.length) {
			val end = when {
				text.startsWith("//", i) -> text.indexOf('\n', i).let { if (it < 0) text.length else it }
				text.startsWith("/*", i) -> text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
				else -> { i++; continue }
			}
			for (k in i until end) if (text[k] != '\n') out[k] = ' '
			i = end
		}
		return out.toString()
	}

	/** (1-based line, registry method) of each unmarked HUD element call in a file that registers no rectangle. */
	fun violations(text: String, markedNear: (List<String>, Int, (String) -> Boolean) -> Boolean): List<Pair<Int, String>> {
		val code = withoutComments(text)
		if (REGISTER.containsMatchIn(code)) return emptyList()
		val lines = text.reader().readLines()
		return CALL.findAll(code).map { call ->
			code.substring(0, call.range.first).count { it == '\n' } to call.groupValues[1]
		}.filter { (line, _) -> !markedNear(lines, line) { MARKER.containsMatchIn(it) } }.map { (line, method) -> line + 1 to method }.toList()
	}
}

val keepClearAdvice = "A HUD element that draws something a player reads or watches registers its rectangle, from its own init and next to the draw " +
	"code: 'HudKeepClear.register(Part::keepClear)', where keepClear adds the same box the element draws (one function for both). The step popup " +
	"places itself by that list, so a part left off it can be covered. An element with nothing to keep clear of (a full-screen fade, a bar vanilla " +
	"draws that this takes away) ends the call line, or the contiguous // lines above it, with '// keep-clear-ok: <reason>'."

/** The problems (file:line message) in the .java files under {@code root}, relative to {@code base}. */
val keepClearProblems: (File, File, (List<String>, Int, (String) -> Boolean) -> Boolean) -> List<String> = { root, base, markedNear ->
	val problems = mutableListOf<String>()
	root.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.toList().sorted().forEach { source ->
		val lines = source.reader().readLines()
		KeepClearGate.violations(source.readText(), markedNear).forEach { (n, method) ->
			problems.add("${source.relativeTo(base)}:$n HudElementRegistry.$method with no HudKeepClear.register in the file: ${lines[n - 1].trim()}")
		}
	}
	problems
}
// Shared with checkGametestFixtures (gametest.gradle.kts), as record-holder-gate.gradle.kts does.
extra["keepClearProblems"] = keepClearProblems
extra["keepClearAdvice"] = keepClearAdvice

tasks.register("checkKeepClearGate") {
	group = "verification"
	description = "Fails on a client file that adds a HUD element, registers nothing with HudKeepClear and carries no '// keep-clear-ok: <reason>' marker."
	@Suppress("UNCHECKED_CAST")
	val markedNear = project.extra["markedNear"] as (List<String>, Int, (String) -> Boolean) -> Boolean
	val root = projectDir
	val source = file("src/client/java")
	val problemsIn = keepClearProblems
	val advice = keepClearAdvice
	inputs.files(fileTree(source) { include("**/*.java") }).withPropertyName("sources")
	doLast {
		val problems = problemsIn(source, root, markedNear)
		if (problems.isNotEmpty()) {
			throw GradleException("HUD elements that no popup knows to keep clear of. $advice\n  " + problems.joinToString("\n  "))
		}
	}
}

tasks.named("check") { dependsOn("checkKeepClearGate") }
