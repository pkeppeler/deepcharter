// Orphan-comment gate (#1052): palantir-java-format wraps a `//` comment past 120 columns mid-sentence and leaves the last word alone on a
// line of its own; nobody reflows it. This fails the build on a `//` line holding exactly one word that continues the `//` line directly
// above it: the same indent, and the line above ends without sentence-ending punctuation (. ! ? : ;).
//
// Not a hit: a lone word after a line that ends a sentence or a colon (a new thought, or a list heading); a lone `// TODO`, `// FIXME`, `// XXX`,
// `// HACK` or `// NOTE`; a word ending in a colon (a `// colour-ok:` style marker with the reason on the next line); a list item (`-`, `*`, `1.`,
// `2)`); a token that starts with anything but a letter, digit, bracket or quote (a lone `}` of commented-out code); a lone word at another indent
// than the line above, below a line that is not a `//` comment, or below a rule line with no letter or digit (a `// -----` section banner). Scope: the files Spotless formats (src/**/*.java, gradle/OomProbe.java,
// tools/TestOnlyGate.java), because Spotless is what wraps them.
//
// tools/tests/orphan_comment_gate_check.py runs the scan over fixture trees (checkGametestFixtures in gametest.gradle.kts).
object OrphanCommentGate {
	private val COMMENT = Regex("""^(\s*)//\s+(\S.*?)\s*$""")
	private val WORD = Regex("""^[\p{L}\p{N}(`'"][^\s]*$""")
	private val LIST_ITEM = Regex("""^\d+[.)]$""")
	private val MARKER_WORD = Regex("""^(TODO|FIXME|XXX|HACK|NOTE)$""")
	private val HAS_TEXT = Regex("""[\p{L}\p{N}]""")
	private val SENTENCE_END = Regex("""[.!?:;]$""")

	/** 1-based lines of each orphan word in {@code text}, with the word. */
	fun violations(text: String): List<Pair<Int, String>> {
		val lines = text.lines()
		val found = mutableListOf<Pair<Int, String>>()
		for (i in 1 until lines.size) {
			val orphan = COMMENT.matchEntire(lines[i]) ?: continue
			val word = orphan.groupValues[2]
			if (!WORD.matches(word) || LIST_ITEM.matches(word) || MARKER_WORD.matches(word) || word.endsWith(":")) continue
			val above = COMMENT.matchEntire(lines[i - 1]) ?: continue
			if (above.groupValues[1] != orphan.groupValues[1] || SENTENCE_END.containsMatchIn(above.groupValues[2]) ||
				!HAS_TEXT.containsMatchIn(above.groupValues[2])) continue
			found.add(i + 1 to word)
		}
		return found
	}
}

val orphanCommentAdvice = "Palantir wrapped a // comment and left its last word alone on a line. Reflow the comment to 120 columns: join the " +
	"word to the line above and re-wrap there if the line runs over."

/** The problems (file:line message) in the .java files under {@code root}, relative to {@code base}. */
val orphanCommentProblems: (Iterable<File>, File) -> List<String> = { files, base ->
	val problems = mutableListOf<String>()
	files.filter { it.isFile && it.name.endsWith(".java") }.sorted().forEach { source ->
		OrphanCommentGate.violations(source.readText()).forEach { (n, word) ->
			problems.add("${source.relativeTo(base)}:$n one-word comment line '$word' continues the comment above")
		}
	}
	problems
}
// Shared with checkGametestFixtures (gametest.gradle.kts), as keep-clear-gate.gradle.kts does.
extra["orphanCommentProblems"] = orphanCommentProblems
extra["orphanCommentAdvice"] = orphanCommentAdvice

tasks.register("checkOrphanCommentGate") {
	group = "verification"
	description = "Fails on a // comment line of one word that continues the // line above it (a Spotless wrap left it)."
	val root = projectDir
	val sources = fileTree(root) { include("src/**/*.java", "gradle/OomProbe.java", "tools/TestOnlyGate.java") }
	val problemsIn = orphanCommentProblems
	val advice = orphanCommentAdvice
	inputs.files(sources).withPropertyName("sources")
	doLast {
		val problems = problemsIn(sources.files, root)
		if (problems.isNotEmpty()) {
			throw GradleException("One-word orphan comment lines. $advice\n  " + problems.joinToString("\n  "))
		}
	}
}

tasks.named("check") { dependsOn("checkOrphanCommentGate") }
