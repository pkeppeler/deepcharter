// Record-holder gate (#646, #644): a private or package-private static nested class that only holds values is a record. This fails the
// build on one in src/main/java, src/client/java and src/gametest/java unless the class line, or the contiguous `//` lines above it,
// carries `// class-ok: <reason>` (an identity the code relies on, say, or a constructor that must stay a class).
//
// The scan is source text, not an Error Prone BugChecker: the build already runs the other source gates this way and a checker needs
// the compiler's --add-exports plumbing, a second test harness and a fixture compile for no extra precision on a rule this syntactic.
//
// What it flags: `[private] static [final] class Name [implements ...]` whose members are, besides static members and methods,
//   * one or more instance fields, every one `final` with no initializer, and
//   * exactly one constructor that only assigns each field from a distinct parameter (`this.f = p;` or `f = p;`), one parameter per field.
// What it leaves alone (false positives cost more than misses): a public or protected class, a non-static inner class (it may use the
// outer instance), an abstract class, an annotated class, a class with `extends`, a generic bound it cannot read, a nested type, an instance
// initializer, a mutable, initialised, transient or volatile instance field, a second constructor, a constructor doing anything else
// (a null check, a copy, a `super(...)` call), and a file that uses object identity (IdentityHashMap, identityHashCode,
// synchronized): a record's equals compares values, so a class that relies on identity is not a drop-in record.
// Known blind spot: a class compared with `==`, or kept in a HashMap/HashSet, changes behaviour as a record. The gate cannot see that
// cheaply, so whoever converts a class checks its uses; a class that must stay takes the marker.
//
// tools/tests/record_holder_gate_check.py runs the scan over fixture trees (checkGametestFixtures in gametest.gradle.kts).
object RecordHolderGate {
	private val DECLARATION = Regex("""^[ \t]*(?:private[ \t]+)?static[ \t]+(?:final[ \t]+)?class[ \t]+(\w+)""", RegexOption.MULTILINE)
	private val IDENTITY = Regex("""\bIdentityHashMap\b|\bidentityHashCode\b|\bsynchronized\b""")
	private val MARKER = Regex("""//\s*class-ok:\s*\S""")
	private val ANNOTATION = Regex("""@\w+(?:\.\w+)*(?:\s*\([^)]*\))?""")
	private val ASSIGNMENT = Regex("""(?:this\s*\.\s*)?(\w+)\s*=\s*(\w+)""")
	private val NESTED_TYPE = Regex("""\b(?:class|interface|enum|record)\b""")

	/** {@code text} with every comment turned into spaces, newlines kept, so braces in comments never count and offsets still line up. */
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

	private fun matching(code: String, open: Int): Int {
		var depth = 0
		for (i in open until code.length) {
			if (code[i] == '{') depth++
			if (code[i] == '}' && --depth == 0) return i
		}
		return -1
	}

	/** Parameter names of a constructor's parameter list, or null when a name cannot be read. */
	private fun parameterNames(list: String): List<String>? {
		val parts = mutableListOf<String>()
		var depth = 0
		var start = 0
		list.forEachIndexed { i, c ->
			when (c) {
				'<', '(' -> depth++
				'>', ')' -> depth--
				',' -> if (depth == 0) { parts.add(list.substring(start, i)); start = i + 1 }
			}
		}
		parts.add(list.substring(start))
		if (parts.size == 1 && parts[0].isBlank()) return emptyList()
		return parts.map { Regex("""(\w+)\s*$""").find(it.trim())?.groupValues?.get(1) ?: return null }
	}

	/** Whether the body (between the class braces) is a pure value holder, by the rule in the header. */
	private fun holdsOnlyValues(name: String, body: String): Boolean {
		val fields = mutableListOf<String>()
		var constructor: Pair<List<String>, String>? = null
		var i = 0
		var member = StringBuilder()
		while (i < body.length) {
			val c = body[i]
			if (c == ';') {
				val text = member.toString().replace(ANNOTATION, " ").trim()
				member = StringBuilder()
				i++
				if (text.isEmpty()) continue
				val words = text.split(Regex("""\s+"""))
				var flat = text
				while (flat.contains('<')) { val next = flat.replace(Regex("<[^<>]*>"), ""); if (next == flat) return false; flat = next }
				if (NESTED_TYPE.containsMatchIn(text)) return false
				if (words.contains("static")) continue
				if (text.contains('(')) return false
				if (!words.contains("final") || text.contains('=') || flat.contains(',') || words.any { it == "transient" || it == "volatile" }) return false
				fields.add(words.last())
			} else if (c == '{') {
				val end = matching(body, i)
				if (end < 0) return false
				val header = member.toString().replace(ANNOTATION, " ").trim()
				val block = body.substring(i + 1, end)
				member = StringBuilder()
				i = end + 1
				if (header.contains('=')) { member.append(header).append(" = {}"); continue }
				if (header.isEmpty()) return false
				if (NESTED_TYPE.containsMatchIn(header.substringBefore('('))) return false
				if (header.startsWith("static") && !header.contains('(')) continue
				val open = header.indexOf('(')
				if (open < 0) return false
				val before = header.substring(0, open).trim().split(Regex("""\s+"""))
				if (before.last() != name) continue
				if (constructor != null) return false
				val params = parameterNames(header.substring(open + 1, header.lastIndexOf(')'))) ?: return false
				if (header.substring(header.lastIndexOf(')') + 1).trim().isNotEmpty()) return false
				constructor = params to block
			} else {
				member.append(c)
				i++
			}
		}
		if (member.isNotBlank() || constructor == null || fields.isEmpty()) return false
		val (params, block) = constructor
		val statements = block.split(';').map { it.trim() }.filter { it.isNotEmpty() }
		val assigned = mutableMapOf<String, String>()
		for (statement in statements) {
			val match = ASSIGNMENT.matchEntire(statement) ?: return false
			if (assigned.put(match.groupValues[1], match.groupValues[2]) != null) return false
		}
		return assigned.keys == fields.toSet() && fields.size == params.size && assigned.values.toSet() == params.toSet() &&
			assigned.values.size == params.size
	}

	/** Lines (1-based) of {@code text}, already passed through blankLiterals, that declare a value-holder class with no marker. */
	fun violations(text: String, markedNear: (List<String>, Int, (String) -> Boolean) -> Boolean): List<Int> {
		val code = withoutComments(text)
		if (IDENTITY.containsMatchIn(code)) return emptyList()
		val lines = text.reader().readLines()
		val found = mutableListOf<Int>()
		for (declaration in DECLARATION.findAll(code)) {
			val start = declaration.range.first
			val line = code.substring(0, start).count { it == '\n' }
			val open = code.indexOf('{', start)
			if (open < 0) continue
			val header = code.substring(declaration.range.last + 1, open)
			if (Regex("""\bextends\b""").containsMatchIn(header) || header.contains(';')) continue
			// A class that is not at member level (inside a method) or that follows an annotation line is left alone.
			val previous = lines.getOrNull(line - 1)?.trim().orEmpty()
			if (previous.startsWith("@")) continue
			val end = matching(code, open)
			if (end < 0) continue
			if (!holdsOnlyValues(declaration.groupValues[1], code.substring(open + 1, end))) continue
			if (markedNear(lines, line) { MARKER.containsMatchIn(it) }) continue
			found.add(line + 1)
		}
		return found
	}
}

val recordHolderAdvice = "A private or package-private static nested class that only holds final values assigned straight from its constructor is a record " +
	"(deepcharter-conventions, #644): make it one. A class that must stay one (it relies on identity, say) ends its declaration line, " +
	"or the contiguous // lines above it, with '// class-ok: <reason>'."

/** The problems (file:line message) in the .java files under {@code root}, relative to {@code base}. */
val recordHolderProblems: (File, File, (String) -> String, (List<String>, Int, (String) -> Boolean) -> Boolean) -> List<String> = { root, base, blankLiterals, markedNear ->
	val problems = mutableListOf<String>()
	root.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.toList().sorted().forEach { source ->
		val lines = source.reader().readLines()
		RecordHolderGate.violations(blankLiterals(source.readText()), markedNear).forEach { n ->
			problems.add("${source.relativeTo(base)}:$n is a value holder that is not a record: ${lines[n - 1].trim()}")
		}
	}
	problems
}
// Shared with checkGametestFixtures (gametest.gradle.kts), as entity-query-gate.gradle.kts does.
extra["recordHolderProblems"] = recordHolderProblems
extra["recordHolderAdvice"] = recordHolderAdvice

tasks.register("checkRecordHolderGate") {
	group = "verification"
	description = "Fails on a private value-holder nested class that is not a record and carries no '// class-ok: <reason>' marker."
	@Suppress("UNCHECKED_CAST")
	val blankLiterals = project.extra["blankLiterals"] as (String) -> String
	@Suppress("UNCHECKED_CAST")
	val markedNear = project.extra["markedNear"] as (List<String>, Int, (String) -> Boolean) -> Boolean
	val root = projectDir
	val roots = listOf(file("src/main/java"), file("src/client/java"), file("src/gametest/java"))
	val problemsIn = recordHolderProblems
	val advice = recordHolderAdvice
	roots.forEachIndexed { i, r -> inputs.files(fileTree(r) { include("**/*.java") }).withPropertyName("sources$i") }
	doLast {
		val problems = roots.flatMap { problemsIn(it, root, blankLiterals, markedNear) }
		if (problems.isNotEmpty()) {
			throw GradleException("Value-holder classes that are not records. $advice\n  " + problems.joinToString("\n  "))
		}
	}
}

tasks.named("check") { dependsOn("checkRecordHolderGate") }
