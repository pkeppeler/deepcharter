// Input literal gate (#1304): Minecraft 26.3 runs on SDL, which numbers mouse buttons from 1, so `event.button() == 0` is not the left
// button, and a raw key code is a key no rebinding or layout reaches. This fails the build on one in code under src/client/java.
//
// It is a scan of whole statements (a formatter wraps before `==` and `&&`), with no type information, so it keys on the accessors of
// the input events (`.button()` and `.key()`):
//   - `.button()` or `.key()` compared (== or !=) with a number or a char literal, in either order
//   - `new MouseButtonInfo(` with a number as its first argument
// Compare with InputConstants.MOUSE_BUTTON_LEFT / MOUSE_BUTTON_RIGHT / KEY_* instead. A statement that must keep one carries
// `// input-literal: <reason>` (the reason is required) on a line of the statement or the comment lines above it (`markedNear`).
// Not seen, the price of no type information: a `switch` on `.button()` or `.key()`, a button number handed through a variable or a
// field, and a literal compared with a differently named accessor.
//
// config/input-literal-gate/Flagged.java.txt must be flagged on every single-tab code line (a continuation line takes two tabs) and
// Allowed.java.txt on none: `checkInputLiteralGate` scans both before the real scan, so a change that makes the pattern miss fails
// here, not silently.
object InputLiteralGate {
	private val PATTERNS = listOf(
		Regex("""\.\s*(button|key)\s*\(\s*\)\s*[=!]=\s*(-?\d|'')"""),
		Regex("""(\d|'')\s*[=!]=\s*[\w.()\s]*\.\s*(button|key)\s*\(\s*\)"""),
		Regex("""\bnew\s+MouseButtonInfo\s*\(\s*-?\d"""),
	)
	private val MARKER = Regex("""//\s*input-literal:\s*\S""")

	/** {@code line} without its comments; {@code inBlock} carries an open block comment to the next line. */
	private fun code(line: String, inBlock: BooleanArray): String {
		val out = StringBuilder()
		var i = 0
		while (i < line.length) {
			if (inBlock[0]) {
				val end = line.indexOf("*/", i)
				if (end < 0) i = line.length else { inBlock[0] = false; i = end + 2 }
			} else if (line.startsWith("//", i)) {
				i = line.length
			} else if (line.startsWith("/*", i)) {
				inBlock[0] = true
				i += 2
			} else {
				out.append(line[i])
				i++
			}
		}
		return out.toString()
	}

	/** First lines (1-based) of the statements of {@code text}, already passed through blankLiterals, with an input literal and no marker. */
	fun violations(text: String, markedNear: (List<String>, Int, (String) -> Boolean) -> Boolean): List<Int> {
		val lines = text.reader().readLines()
		val inBlock = booleanArrayOf(false)
		val codes = lines.map { code(it, inBlock) }
		val found = mutableListOf<Int>()
		var start = 0
		val joined = StringBuilder()
		codes.forEachIndexed { index, line ->
			if (joined.isBlank()) start = index
			joined.append(line).append(' ')
			val trimmed = line.trim()
			if (trimmed.endsWith(";") || trimmed.endsWith("{") || trimmed.endsWith("}")) {
				if (PATTERNS.any { it.containsMatchIn(joined) } && !markedNear(lines, start) { MARKER.containsMatchIn(it) }) {
					found.add(start + 1)
				}
				joined.setLength(0)
			} else if (trimmed.isEmpty() && joined.isBlank()) {
				joined.setLength(0)
			}
		}
		return found
	}
}

tasks.register("checkInputLiteralGate") {
	group = "verification"
	description = "Fails on a literal mouse button or key code compared in client code."
	@Suppress("UNCHECKED_CAST")
	val blankLiterals = project.extra["blankLiterals"] as (String) -> String
	@Suppress("UNCHECKED_CAST")
	val markedNear = project.extra["markedNear"] as (List<String>, Int, (String) -> Boolean) -> Boolean
	val clientSources = fileTree(file("src/client/java")) { include("**/*.java") }
	val fixtureDir = file("config/input-literal-gate")
	val root = projectDir
	inputs.files(clientSources).withPropertyName("clientSources")
	inputs.dir(fixtureDir).withPropertyName("fixtures")
	doLast {
		val flagged = File(fixtureDir, "Flagged.java.txt").readText()
		val allowed = File(fixtureDir, "Allowed.java.txt").readText()
		val codeLine = Regex("""^\t(?!//)\S""")
		val expected = flagged.reader().readLines().withIndex().filter { (_, line) -> codeLine.containsMatchIn(line) }.map { it.index + 1 }
		if (expected.size < 6) throw GradleException("config/input-literal-gate/Flagged.java.txt has ${expected.size} code lines, expected at least 6")
		val actual = InputLiteralGate.violations(blankLiterals(flagged), markedNear)
		if (actual != expected) {
			throw GradleException("The input literal gate is broken: Flagged.java.txt should be flagged on lines $expected, was $actual")
		}
		val wrongly = InputLiteralGate.violations(blankLiterals(allowed), markedNear)
		if (wrongly.isNotEmpty()) {
			throw GradleException("The input literal gate is broken: Allowed.java.txt should not be flagged, was on lines $wrongly")
		}

		val problems = mutableListOf<String>()
		clientSources.files.sorted().forEach { source ->
			val lines = source.reader().readLines()
			InputLiteralGate.violations(blankLiterals(source.readText()), markedNear).forEach { n ->
				problems.add("${source.relativeTo(root)}:$n: ${lines[n - 1].trim()}")
			}
		}
		if (problems.isNotEmpty()) {
			throw GradleException(
				"Literal mouse button or key code in client code. SDL numbers mouse buttons from 1: compare with " +
					"InputConstants.MOUSE_BUTTON_LEFT / MOUSE_BUTTON_RIGHT / KEY_*; a line that must keep one ends with " +
					"'// input-literal: <reason>'.\n  " + problems.joinToString("\n  "),
			)
		}
	}
}

tasks.named("check") { dependsOn("checkInputLiteralGate") }
