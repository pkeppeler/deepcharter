// Null-marked gate (step 7c of #550): NullAway checks only @NullMarked packages (OnlyNullMarked), so a package without the mark is
// silently unchecked. This fails the build on a package under src/main/java, src/client/java or src/gametest/java that has Java
// sources and no package-info.java carrying @NullMarked. The annotation is looked for outside comments.
//
// tools/tests/null_marked_gate_check.py runs the scan over fixture trees (checkGametestFixtures in gametest.gradle.kts).
object NullMarkedGate {
	private val COMMENT = Regex("""/\*.*?\*/|//[^\n]*""", RegexOption.DOT_MATCHES_ALL)
	private val MARK = Regex("""@(?:org\.jspecify\.annotations\.)?NullMarked\b""")

	fun isMarked(packageInfo: File): Boolean = packageInfo.isFile && MARK.containsMatchIn(packageInfo.readText().replace(COMMENT, " "))
}

val nullMarkedAdvice = "Add a package-info.java to the package with '@NullMarked' above the package line and 'import org.jspecify.annotations.NullMarked;', " +
	"then fix the NullAway findings it brings (see the Null safety bullet in the deepcharter-conventions skill)."

/** The problems (message) for the packages under the source root {@code root} that have Java sources and no @NullMarked package-info. */
val nullMarkedProblems: (File, File) -> List<String> = { root, base ->
	root.walkTopDown().filter { it.isDirectory }.toList().sorted().mapNotNull { dir ->
		val hasSources = dir.listFiles { f -> f.isFile && f.name.endsWith(".java") && f.name != "package-info.java" }!!.isNotEmpty()
		val info = File(dir, "package-info.java")
		if (!hasSources || NullMarkedGate.isMarked(info)) null
		else "${dir.relativeTo(root).path.replace(File.separatorChar, '.')} has Java sources but no package-info.java with @NullMarked (${info.relativeTo(base)} " +
			(if (info.isFile) "lacks the annotation" else "is missing") + ")"
	}
}
// Shared with checkGametestFixtures (gametest.gradle.kts), as entity-query-gate.gradle.kts does.
extra["nullMarkedProblems"] = nullMarkedProblems
extra["nullMarkedAdvice"] = nullMarkedAdvice

tasks.register("checkNullMarkedGate") {
	group = "verification"
	description = "Fails on a package with Java sources and no package-info.java carrying @NullMarked."
	val base = projectDir
	val roots = listOf(file("src/main/java"), file("src/client/java"), file("src/gametest/java"))
	val problemsIn = nullMarkedProblems
	val advice = nullMarkedAdvice
	roots.forEachIndexed { i, r -> inputs.files(fileTree(r) { include("**/*.java") }).withPropertyName("sources$i") }
	doLast {
		val problems = roots.flatMap { problemsIn(it, base) }
		if (problems.isNotEmpty()) {
			throw GradleException("Packages NullAway does not check. $advice\n  " + problems.joinToString("\n  "))
		}
	}
}

tasks.named("check") { dependsOn("checkNullMarkedGate") }
