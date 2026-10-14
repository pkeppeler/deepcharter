// Entity-query gate (#380, m2-rules.md): an entity query sees only the entities of loaded chunks, so "nothing found" can mean "not
// loaded". This fails the build on one in code under src/main/java and src/client/java unless the statement it is in, or the comment line above that statement, carries
// `// entity-query: <reason>`, a reason that says why the queried area is always loaded where the code runs (around the acting player
// or pod, say) or that a miss is harmless. A gameplay decision or a cleanup that must not miss goes through saved data instead.
//
// What it flags: getEntitiesOfClass(, getEntities(, getNearbyEntities(, getNearestEntity(, getEntityGetter(, getAllEntities(, getEntityOrPart(,
// a lookup of one entity by id, getEntity(<argument>), and a method reference to any of them (level::getEntity). A call with no argument,
// DamageSource.getEntity(), is not one; a getEntity( that ends its line is, because its argument is on the next. Comments and string
// literals are not scanned, and the marker's reason is required. Known blind spot: the scan is line by line, so a name split from its own
// parenthesis by a line break slips through.
//
// tools/tests/entity_query_gate_check.py runs the scan over fixture trees (checkGametestFixtures in gametest.gradle.kts), so a change that
// makes the pattern miss fails there, not silently.
object EntityQueryGate {
	private val QUERY = Regex("""\b(?:getEntitiesOfClass|getEntities|getNearbyEntities|getNearestEntity|getEntityGetter|getAllEntities|getEntityOrPart)\s*\(|\bgetEntity\s*\(\s*(?:[^)\s]|$)|::\s*(?:getEntitiesOfClass|getEntities|getNearbyEntities|getNearestEntity|getEntityGetter|getAllEntities|getEntityOrPart|getEntity)\b""")
	private val MARKER = Regex("""//\s*entity-query:\s*\S""")
	private val BLOCK_COMMENT = Regex("""/\*.*?\*/""")

	/** Lines (1-based) of {@code text}, already passed through blankLiterals, that hold an entity query and no marker on their statement or the comment lines above it. */
	fun violations(text: String, markedNear: (List<String>, Int, (String) -> Boolean) -> Boolean): List<Int> {
		val found = mutableListOf<Int>()
		var inBlockComment = false
		val lines = text.reader().readLines()
		lines.forEachIndexed { index, line ->
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
			val commentAt = code.indexOf("//")
			if (commentAt >= 0) code = code.substring(0, commentAt)
			code = code.replace(BLOCK_COMMENT, " ")
			if (!QUERY.containsMatchIn(code)) return@forEachIndexed
			val marked = markedNear(lines, index) { MARKER.containsMatchIn(it) }
			if (!marked) found.add(index + 1)
		}
		return found
	}
}

val entityQueryAdvice = "Decide a gameplay rule or a cleanup from saved data (a SavedData, an attachment), not from an entity query. A query over the " +
	"area around the acting player or pod, where a miss is harmless, ends with '// entity-query: <reason>'. See m2-rules.md."

/** The problems (file:line message) in the .java files under {@code root}, relative to {@code base}. */
val entityQueryProblems: (File, File, (String) -> String, (List<String>, Int, (String) -> Boolean) -> Boolean) -> List<String> = { root, base, blankLiterals, markedNear ->
	val problems = mutableListOf<String>()
	root.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.toList().sorted().forEach { source ->
		val lines = source.reader().readLines()
		EntityQueryGate.violations(blankLiterals(source.readText()), markedNear).forEach { n ->
			problems.add("${source.relativeTo(base)}:$n asks an entity query, which sees only loaded chunks, so 'nothing found' can mean 'not loaded': ${lines[n - 1].trim()}")
		}
	}
	problems
}
// Shared with checkGametestFixtures (gametest.gradle.kts), which runs the scan over fixture trees. Applied scripts cannot share
// declarations, so they go in the project's extra properties.
extra["entityQueryProblems"] = entityQueryProblems
extra["entityQueryAdvice"] = entityQueryAdvice

tasks.register("checkEntityQueryGate") {
	group = "verification"
	description = "Fails on an entity query in production code that carries no '// entity-query: <reason>' marker."
	@Suppress("UNCHECKED_CAST")
	val blankLiterals = project.extra["blankLiterals"] as (String) -> String
	@Suppress("UNCHECKED_CAST")
	val markedNear = project.extra["markedNear"] as (List<String>, Int, (String) -> Boolean) -> Boolean
	val root = projectDir
	val mainRoot = file("src/main/java")
	val clientRoot = file("src/client/java")
	val productionSources = fileTree(mainRoot) { include("**/*.java") } + fileTree(clientRoot) { include("**/*.java") }
	val problemsIn = entityQueryProblems
	val advice = entityQueryAdvice
	inputs.files(productionSources).withPropertyName("productionSources")
	doLast {
		val problems = listOf(mainRoot, clientRoot).flatMap { problemsIn(it, root, blankLiterals, markedNear) }
		if (problems.isNotEmpty()) {
			throw GradleException("Entity queries in production code. $advice\n  " + problems.joinToString("\n  "))
		}
	}
}

tasks.named("check") { dependsOn("checkEntityQueryGate") }
