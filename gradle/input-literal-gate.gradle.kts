// Input literal gate (#1304): Minecraft 26.3 runs on SDL, which numbers mouse buttons from 1, so `event.button() == 0` is not the left
// button, and a raw key code is a key no rebinding or layout reaches. This fails the build on one in code under src/client/java.
//
// It is a line scan, with no type information, so it keys on the accessors of the input events (`.button()` and `.key()`):
//   - `.button()` or `.key()` compared (== or !=) with a number or a char literal, in either order
//   - `new MouseButtonInfo(` with a number as its first argument
// Compare with InputConstants.MOUSE_BUTTON_LEFT / MOUSE_BUTTON_RIGHT / KEY_* instead. A line that must keep one ends with
// `// input-literal: <reason>`; the reason is required. Comment-only lines and trailing comments are not scanned. A `switch` on
// `.button()` and a button number handed through a variable are not seen: that is the price of no type information.
//
// config/input-literal-gate/Flagged.java.txt must be flagged on every indented code line and Allowed.java.txt on none:
// `checkInputLiteralGate` scans both before the real scan, so a change that makes the pattern miss fails here, not silently.
object InputLiteralGate {
	private val PATTERNS = listOf(
		Regex("""\.\s*(button|key)\s*\(\s*\)\s*[=!]=\s*(-?\d|'')"""),
		Regex("""(\d|'')\s*[=!]=\s*[\w.()\s]*\.\s*(button|key)\s*\(\s*\)"""),
		Regex("""\bnew\s+MouseButtonInfo\s*\(\s*-?\d"""),
	)
	private val MARKER = Regex("""//\s*input-literal:\s*\S""")
	private val BLOCK_COMMENT = Regex("""/\*.*?\*/""")

	/** Lines (1-based) of {@code text}, already passed through blankLiterals, that hold an input literal and no marker. */
	fun violations(text: String): List<Int> {
		val found = mutableListOf<Int>()
		var inBlockComment = false
		text.reader().readLines().forEachIndexed { index, line ->
			var code = line
			if (inBlockComment) {
				if (!code.contains("*/")) return@forEachIndexed
				code = code.substring(code.indexOf("*/") + 2)
				inBlockComment = false
			}
			val trimmed = code.trim()
			if (trimmed.startsWith("//")) return@forEachIndexed
			if (trimmed.startsWith("/*") && !trimmed.contains("*/")) {
				inBlockComment = true
				return@forEachIndexed
			}
			if (MARKER.containsMatchIn(code)) return@forEachIndexed
			val commentAt = code.indexOf("//")
			if (commentAt >= 0) code = code.substring(0, commentAt)
			code = code.replace(BLOCK_COMMENT, " ")
			if (PATTERNS.any { it.containsMatchIn(code) }) found.add(index + 1)
		}
		return found
	}
}

tasks.register("checkInputLiteralGate") {
	group = "verification"
	description = "Fails on a literal mouse button or key code compared in client code."
	@Suppress("UNCHECKED_CAST")
	val blankLiterals = project.extra["blankLiterals"] as (String) -> String
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
		val actual = InputLiteralGate.violations(blankLiterals(flagged))
		if (actual != expected) {
			throw GradleException("The input literal gate is broken: Flagged.java.txt should be flagged on lines $expected, was $actual")
		}
		val wrongly = InputLiteralGate.violations(blankLiterals(allowed))
		if (wrongly.isNotEmpty()) {
			throw GradleException("The input literal gate is broken: Allowed.java.txt should not be flagged, was on lines $wrongly")
		}

		val problems = mutableListOf<String>()
		clientSources.files.sorted().forEach { source ->
			val lines = source.reader().readLines()
			InputLiteralGate.violations(blankLiterals(source.readText())).forEach { n ->
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
