// Colour gate (#226, ADR 0032): the client draws with colours from the UI theme, which a resource pack can replace, so a colour
// literal in client code is a look a pack cannot reach. This fails the build on one.
//
// What it flags, in code under src/client/java outside client/theme/ (the loader, where a colour is parsed):
//   - a hex literal of six or more digits, 0xFF7CFC9A, 0x00FFFFFF, 0xFF_00_00
//   - ARGB.color(, ARGB.colorFromFloat(, ARGB.opaque( and argb( calls, CommonColors.NAME, a ChatFormatting colour such as
//     ChatFormatting.GOLD, TextColor.fromRgb( and Integer.parseInt(text, 16)
// A line that must keep one (a mask, say) ends with `// colour-ok: <reason>`; the reason is required. Comment-only lines and the
// text of a trailing comment are not scanned. Decimal colours and `ARGB.white(alpha)` are not flagged.
//
// config/colour-gate/Flagged.java.txt must be flagged on every indented code line and Allowed.java.txt on none: `checkColourGate` runs the
// scan over both before the real one, so a change that makes the pattern miss fails here, not silently.
object ColourGate {
	private val PATTERNS = listOf(
		Regex("""0[xX][0-9A-Fa-f_]{6,}"""),
		Regex("""\bargb\s*\("""),
		Regex("""\bARGB\s*\.\s*color\s*\("""),
		Regex("""\bCommonColors\s*\."""),
		Regex("""\bARGB\s*\.\s*(colorFromFloat|opaque)\s*\("""),
		Regex("""\bChatFormatting\s*\.\s*(BLACK|DARK_BLUE|DARK_GREEN|DARK_AQUA|DARK_RED|DARK_PURPLE|GOLD|GRAY|DARK_GRAY|BLUE|GREEN|AQUA|RED|LIGHT_PURPLE|YELLOW|WHITE)\b"""),
		Regex("""\bTextColor\s*\.\s*fromRgb\s*\("""),
		Regex("""\bInteger\s*\.\s*parseInt\s*\([^;]*,\s*16\s*\)"""),
	)
	private val MARKER = Regex("""//\s*colour-ok:\s*\S""")
	private val BLOCK_COMMENT = Regex("""/\*.*?\*/""")

	/** Lines (1-based) of {@code text}, already passed through blankLiterals, that hold a colour literal and no marker. */
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

tasks.register("checkColourGate") {
	group = "verification"
	description = "Fails on a colour literal in client code outside client/theme."
	@Suppress("UNCHECKED_CAST")
	val blankLiterals = project.extra["blankLiterals"] as (String) -> String
	val clientRoot = file("src/client/java")
	val themeDir = File(clientRoot, "io/github/pkeppeler/deepcharter/client/theme")
	val clientSources = fileTree(clientRoot) { include("**/*.java") }
	val fixtureDir = file("config/colour-gate")
	val root = projectDir
	inputs.files(clientSources).withPropertyName("clientSources")
	inputs.dir(fixtureDir).withPropertyName("fixtures")
	doLast {
		val flagged = File(fixtureDir, "Flagged.java.txt").readText()
		val allowed = File(fixtureDir, "Allowed.java.txt").readText()
		val codeLine = Regex("""^\t(?!//)\S""")
		val expected = flagged.reader().readLines().withIndex().filter { (_, line) -> codeLine.containsMatchIn(line) }.map { it.index + 1 }
		if (expected.size < 6) throw GradleException("config/colour-gate/Flagged.java.txt has ${expected.size} code lines, expected at least 6")
		val actual = ColourGate.violations(blankLiterals(flagged))
		if (actual != expected) {
			throw GradleException("The colour gate is broken: Flagged.java.txt should be flagged on lines $expected, was $actual")
		}
		val wrongly = ColourGate.violations(blankLiterals(allowed))
		if (wrongly.isNotEmpty()) {
			throw GradleException("The colour gate is broken: Allowed.java.txt should not be flagged, was on lines $wrongly")
		}

		val problems = mutableListOf<String>()
		clientSources.files.sorted().forEach { source ->
			if (source.startsWith(themeDir)) return@forEach
			val lines = source.reader().readLines()
			ColourGate.violations(blankLiterals(source.readText())).forEach { n ->
				problems.add("${source.relativeTo(root)}:$n: ${lines[n - 1].trim()}")
			}
		}
		if (problems.isNotEmpty()) {
			throw GradleException(
				"Colour literals in client code. Colours come from the UI theme (assets/deepcharter/theme/*.json, read " +
					"through client/theme); a line that must keep one ends with '// colour-ok: <reason>'. See docs/design/skins.md.\n  " +
					problems.joinToString("\n  "),
			)
		}
	}
}

tasks.named("check") { dependsOn("checkColourGate") }
