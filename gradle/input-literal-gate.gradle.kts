// Input literal gate (#1304): Minecraft 26.3 runs on SDL, which numbers mouse buttons from 1, so `event.button() == 0` is not the left
// button, and a raw key code is a key no rebinding or layout reaches. This fails the build on a hand-built literal of either in code
// under src/client/java and src/gametest/java.
//
// It is a scan of whole statements (a formatter wraps before `==` and `&&`), with no type information, so it keys on the accessors of
// the input events (`.button()` and `.key()`):
//   - `.button()` or `.key()` compared (== or !=) with a number or a char literal, in either order
//   - `new MouseButtonInfo(` or `new KeyEvent(` with a number or a char literal as its first argument
//   - in src/gametest only: `.typeChars` (the Fabric runner's text without its key press) and `.charTyped` (a screen's handler
//     called directly), as a call or a method reference (`::`). Type through RealInput.type, which sends the key, the text, then the release, in SDL's order.
// Compare with InputConstants.MOUSE_BUTTON_LEFT / MOUSE_BUTTON_RIGHT / KEY_* instead. A statement that must keep one carries
// `// input-literal: <reason>` (the reason is required) on a line of the statement or the comment lines above it (`markedNear`).
// Not seen, the price of no type information: a `switch` on `.button()` or `.key()`, a button or key number handed through a variable
// or a field, and a literal compared with a differently named accessor.
//
// config/input-literal-gate/Flagged.java.txt must be flagged on every single-tab code line (a continuation line takes two tabs) and
// Allowed.java.txt on none: `checkInputLiteralGate` scans both before the real scan, so a change that makes the pattern miss fails
// here, not silently.
object InputLiteralGate {
	private val PATTERNS = listOf(
		Regex("""\.\s*(button|key)\s*\(\s*\)\s*[=!]=\s*(-?\d|'')"""),
		Regex("""(\d|'')\s*[=!]=\s*[\w.()\s]*\.\s*(button|key)\s*\(\s*\)"""),
		Regex("""\bnew\s+MouseButtonInfo\s*\(\s*-?\d"""),
		Regex("""\bnew\s+KeyEvent\s*\(\s*(-?\d|'')"""),
	)
	// Only in src/gametest: the client's own code calls charTyped, and a test must not.
	private val TEST_PATTERNS = listOf(Regex("""(\.\s*|::\s*)(typeChars|charTyped)\b"""))
	private val MARKER = Regex("""//\s*input-literal:\s*\S""")

	/** A statement with an input literal and no marker: its first line (1-based) and whether it is a test typing outside RealInput.type. */
	data class Violation(val line: Int, val typing: Boolean)

	/** The violations in {@code text}, already passed through blankLiterals. */
	fun violations(
		text: String,
		testCode: Boolean,
		codeLines: (List<String>) -> List<String>,
		markedNear: (List<String>, Int, (String) -> Boolean) -> Boolean,
	): List<Violation> {
		val lines = text.reader().readLines()
		val codes = codeLines(lines)
		val found = mutableListOf<Violation>()
		val patterns = if (testCode) PATTERNS + TEST_PATTERNS else PATTERNS
		var start = 0
		val joined = StringBuilder()
		codes.forEachIndexed { index, line ->
			if (joined.isBlank()) start = index
			joined.append(line).append(' ')
			if (line.endsWith(";") || line.endsWith("{") || line.endsWith("}")) {
				if (patterns.any { it.containsMatchIn(joined) } && !markedNear(lines, start) { MARKER.containsMatchIn(it) }) {
					found.add(Violation(start + 1, testCode && TEST_PATTERNS.any { it.containsMatchIn(joined) }))
				}
				joined.setLength(0)
			} else if (line.isEmpty() && joined.isBlank()) {
				joined.setLength(0)
			}
		}
		return found
	}
}

tasks.register("checkInputLiteralGate") {
	group = "verification"
	description = "Fails on a literal mouse button or key code in client or client test code."
	@Suppress("UNCHECKED_CAST")
	val blankLiterals = project.extra["blankLiterals"] as (String) -> String
	@Suppress("UNCHECKED_CAST")
	val codeLines = project.extra["codeLines"] as (List<String>) -> List<String>
	@Suppress("UNCHECKED_CAST")
	val markedNear = project.extra["markedNear"] as (List<String>, Int, (String) -> Boolean) -> Boolean
	val clientTrees = files(
		fileTree(file("src/client/java")) { include("**/*.java") },
	)
	val testTrees = files(
		fileTree(file("src/gametest/java")) { include("**/*.java") },
	)
	val fixtureDir = file("config/input-literal-gate")
	val root = projectDir
	inputs.files(clientTrees).withPropertyName("clientSources")
	inputs.files(testTrees).withPropertyName("testSources")
	inputs.dir(fixtureDir).withPropertyName("fixtures")
	doLast {
		val flagged = File(fixtureDir, "Flagged.java.txt").readText()
		val allowed = File(fixtureDir, "Allowed.java.txt").readText()
		val codeLine = Regex("""^\t(?!//)\S""")
		val expected = flagged.reader().readLines().withIndex().filter { (_, line) -> codeLine.containsMatchIn(line) }.map { it.index + 1 }
		if (expected.size < 6) throw GradleException("config/input-literal-gate/Flagged.java.txt has ${expected.size} code lines, expected at least 6")
		val actual = InputLiteralGate.violations(blankLiterals(flagged), true, codeLines, markedNear).map { it.line }
		if (actual != expected) {
			throw GradleException("The input literal gate is broken: Flagged.java.txt should be flagged on lines $expected, was $actual")
		}
		val wrongly = InputLiteralGate.violations(blankLiterals(allowed), true, codeLines, markedNear).map { it.line }
		if (wrongly.isNotEmpty()) {
			throw GradleException("The input literal gate is broken: Allowed.java.txt should not be flagged, was on lines $wrongly")
		}

		val clientCode = InputLiteralGate.violations(blankLiterals("class A {\n\tObject a = panel.charTyped(event);\n}\n"), false, codeLines, markedNear)
		if (clientCode.isNotEmpty()) {
			throw GradleException("The input literal gate is broken: a charTyped call outside src/gametest should not be flagged, was on lines $clientCode")
		}

		val problems = mutableListOf<String>()
		val typing = mutableListOf<String>()
		listOf(clientTrees to false, testTrees to true).forEach { (tree, testCode) ->
			tree.files.sorted().forEach { source ->
				val lines = source.reader().readLines()
				InputLiteralGate.violations(blankLiterals(source.readText()), testCode, codeLines, markedNear).forEach { v ->
					(if (v.typing) typing else problems).add("${source.relativeTo(root)}:${v.line}: ${lines[v.line - 1].trim()}")
				}
			}
		}
		val messages = mutableListOf<String>()
		if (typing.isNotEmpty()) {
			messages.add(
				"A test types through typeChars or charTyped: type with RealInput.type(context, \"...\"), which sends the key, the text, then the release " +
					"in SDL's order; a line that must keep one ends with '// input-literal: <reason>'.\n  " + typing.joinToString("\n  "),
			)
		}
		if (problems.isNotEmpty()) {
			messages.add(
				"Literal mouse button or key code in client or client test code. SDL numbers mouse buttons from 1: compare with " +
					"InputConstants.MOUSE_BUTTON_LEFT / MOUSE_BUTTON_RIGHT / KEY_*; a line that must keep one ends with " +
					"'// input-literal: <reason>'.\n  " + problems.joinToString("\n  "),
			)
		}
		if (messages.isNotEmpty()) throw GradleException(messages.joinToString("\n"))
	}
}

tasks.named("check") { dependsOn("checkInputLiteralGate") }
