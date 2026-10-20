// Record-holder gate (#646, #644): a private or package-private static nested class that only holds values is a record. This fails the
// build on one in src/main/java, src/client/java and src/gametest/java unless the class line, or the contiguous `//` lines above it,
// carries `// class-ok: <reason>` (an identity the code relies on, say, or a constructor that must stay a class).
//
// The scan is source text, not an Error Prone BugChecker: the build already runs the other source gates this way and a checker needs
// the compiler's --add-exports plumbing, a second test harness and a fixture compile for no extra precision on a rule this syntactic.
//
// What it flags: `[private] static [final] class Name [implements ...]` whose members are, besides static members and methods,
//   * one or more instance fields, every one `final` with no initializer, and
//   * exactly one constructor that only assigns each field from a distinct parameter (`this.f = p;` or `f = p;`), one parameter per field. A statement
//     `this.f = Objects.requireNonNull(p[, msg]);` counts as that assignment: the class is a record with a compact constructor, and the message says so.
// What it leaves alone (false positives cost more than misses): a public or protected class, a non-static inner class (it may use the
// outer instance), an abstract class, an annotated class, a class with `extends`, a generic bound it cannot read, a nested type, an instance
// initializer, a mutable, initialised, transient or volatile instance field, a second constructor, a constructor doing anything else
// (a copy, a derived field, a `super(...)` call), and a file that uses object identity (IdentityHashMap, identityHashCode): a record's equals
// compares values, so a class that relies on identity is not a drop-in record. `synchronized` does not exempt a file; a class used as a lock
// takes the marker.
// Known blind spot: a class compared with `==`, or kept in a HashMap/HashSet, changes behaviour as a record. The gate cannot see that
// cheaply, so whoever converts a class checks its uses; a class that must stay takes the marker.
//
// tools/tests/record_holder_gate_check.py runs the scan over fixture trees (checkGametestFixtures in gametest.gradle.kts).
object RecordHolderGate {
	private val DECLARATION = Regex("""^[ \t]*(?:private[ \t]+)?static[ \t]+(?:final[ \t]+)?class[ \t]+(\w+)""", RegexOption.MULTILINE)
	private val IDENTITY = Regex("""\bIdentityHashMap\b|\bidentityHashCode\b""")
	private val MARKER = Regex("""//\s*class-ok:\s*\S""")
	private val ANNOTATION = Regex("""@\w+(?:\.\w+)*(?:\s*\([^)]*\))?""")
	private val ASSIGNMENT = Regex("""(?:this\s*\.\s*)?(\w+)\s*=\s*(?:(\w+)|Objects\s*\.\s*requireNonNull\s*\(\s*(\w+)\s*(?:,.*)?\))""", RegexOption.DOT_MATCHES_ALL)
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

	/** Null when the body (between the class braces) is not a pure value holder by the rule in the header; else whether a constructor null-checks. */
	private fun holdsOnlyValues(name: String, body: String): Boolean? {
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
				while (flat.contains('<')) { val next = flat.replace(Regex("<[^<>]*>"), ""); if (next == flat) return null; flat = next }
				if (NESTED_TYPE.containsMatchIn(text)) return null
				if (words.contains("static")) continue
				if (text.contains('(')) return null
				if (!words.contains("final") || text.contains('=') || flat.contains(',') || words.any { it == "transient" || it == "volatile" }) return null
				fields.add(words.last())
			} else if (c == '{') {
				val end = matching(body, i)
				if (end < 0) return null
				val header = member.toString().replace(ANNOTATION, " ").trim()
				val block = body.substring(i + 1, end)
				member = StringBuilder()
				i = end + 1
				if (header.contains('=')) { member.append(header).append(" = {}"); continue }
				if (header.isEmpty()) return null
				if (NESTED_TYPE.containsMatchIn(header.substringBefore('('))) return null
				if (header.startsWith("static") && !header.contains('(')) continue
				val open = header.indexOf('(')
				if (open < 0) return null
				val before = header.substring(0, open).trim().split(Regex("""\s+"""))
				if (before.last() != name) continue
				if (constructor != null) return null
				val params = parameterNames(header.substring(open + 1, header.lastIndexOf(')'))) ?: return null
				if (header.substring(header.lastIndexOf(')') + 1).trim().isNotEmpty()) return null
				constructor = params to block
			} else {
				member.append(c)
				i++
			}
		}
		if (member.isNotBlank() || constructor == null || fields.isEmpty()) return null
		val (params, block) = constructor
		val statements = block.split(';').map { it.trim() }.filter { it.isNotEmpty() }
		val assigned = mutableMapOf<String, String>()
		var nullChecked = false
		for (statement in statements) {
			val match = ASSIGNMENT.matchEntire(statement) ?: return null
			val source = match.groupValues[2].ifEmpty { match.groupValues[3].also { nullChecked = true } }
			if (assigned.put(match.groupValues[1], source) != null) return null
		}
		val holder = assigned.keys == fields.toSet() && fields.size == params.size && assigned.values.toSet() == params.toSet() &&
			assigned.values.size == params.size
		return if (holder) nullChecked else null
	}

	/** Lines (1-based) of {@code text}, already passed through blankLiterals, that declare a value-holder class with no marker. */
	fun violations(text: String, markedNear: (List<String>, Int, (String) -> Boolean) -> Boolean): List<Pair<Int, Boolean>> {
		val code = withoutComments(text)
		if (IDENTITY.containsMatchIn(code)) return emptyList()
		val lines = text.reader().readLines()
		val found = mutableListOf<Pair<Int, Boolean>>()
		for (declaration in DECLARATION.findAll(code)) {
			val start = declaration.range.first
			val line = code.substring(0, start).count { it == '\n' }
			val open = code.indexOf('{', start)
			if (open < 0) continue
			val header = code.substring(declaration.range.last + 1, open)
			if (Regex("""\bextends\b""").containsMatchIn(header) || header.contains(';')) continue
			// A class on the line after an annotation is left alone.
			val previous = lines.getOrNull(line - 1)?.trim().orEmpty()
			if (previous.startsWith("@")) continue
			val end = matching(code, open)
			if (end < 0) continue
			val nullChecked = holdsOnlyValues(declaration.groupValues[1], code.substring(open + 1, end)) ?: continue
			if (markedNear(lines, line) { MARKER.containsMatchIn(it) }) continue
			found.add(line + 1 to nullChecked)
		}
		return found
	}
}

val recordHolderAdvice = "A private or package-private static nested class whose instance fields are all final and assigned straight from its only " +
	"constructor's parameters is an immutable value holder, and those are records: a record states the shape in one line and gets equals, " +
	"hashCode and toString for free. Replace the class with 'private record Name(Type field, ...) {}' and call field() for the accessors. " +
	"A class that must stay a class (it is a lock, or it relies on identity) ends its declaration line, or the contiguous // lines above it, " +
	"with '// class-ok: <reason>'."

/** The problems (file:line message) in the .java files under {@code root}, relative to {@code base}. */
val recordHolderProblems: (File, File, (String) -> String, (List<String>, Int, (String) -> Boolean) -> Boolean) -> List<String> = { root, base, blankLiterals, markedNear ->
	val problems = mutableListOf<String>()
	root.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.toList().sorted().forEach { source ->
		val lines = source.reader().readLines()
		RecordHolderGate.violations(blankLiterals(source.readText()), markedNear).forEach { (n, nullChecked) ->
			val fix = if (nullChecked) " (convert to a record; move the null check into a compact constructor)" else ""
			problems.add("${source.relativeTo(base)}:$n is a value holder that is not a record$fix: ${lines[n - 1].trim()}")
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
