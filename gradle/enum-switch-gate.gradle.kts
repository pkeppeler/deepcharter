// Enum-switch gate (#1217): a switch over an enum whose `default` hides a constant fails the build, so a new enum member fails at every
// switch that forgot it. Error Prone's MissingCasesInEnumSwitch covers a switch with no default and UnnecessaryDefaultInEnumSwitch an
// unreachable default (both ERROR in build.gradle.kts); this covers the one in between. A default that is the point ends its arm's line,
// or the contiguous `//` lines above it, with `// enum-default: <reason>`.
//
// The scan is tools/EnumSwitchGate.java, run the way the test-only gate runs its tool. It attributes the sources with javac, because only
// the compiler knows a switch is over an enum. A custom Error Prone BugChecker would too, but needs its own subproject, lockfile and
// dependency verification for no extra precision. The cost is one javac attribution of the three source sets together.
// tools/tests/enum_switch_gate_check.py runs it over fixture trees (tools/py tools/tests/gate_checks.py enum_switch).
// Every finding fails. -PenumSwitchList prints every enum switch with a default and how it passes or fails.
tasks.register("checkEnumSwitchGate") {
	group = "verification"
	description = "Fails on an enum switch whose default hides a constant and carries no '// enum-default: <reason>' marker."
	val sourceSets = project.the<SourceSetContainer>()
	val names = listOf("main", "client", "gametest")
	val roots = names.map { file("src/$it/java") }
	// The classpath is every compile classpath less the mod's own class output, which the sources replace.
	val own = names.flatMap { sourceSets[it].output.files }.toSet()
	val classpath = files(names.map { sourceSets[it].compileClasspath }).filter { it !in own }
	val gateSource = file("tools/EnumSwitchGate.java")
	roots.forEachIndexed { i, r -> inputs.files(fileTree(r) { include("**/*.java") }).withPropertyName("sources$i") }
	inputs.files(classpath).withPropertyName("classpath")
	inputs.file(gateSource).withPropertyName("gate")
	val root = projectDir
	val list = providers.gradleProperty("enumSwitchList").isPresent
	inputs.property("list", list)
	doLast {
		val command = buildList {
			add(File(System.getProperty("java.home"), "bin/java").path)
			addAll(listOf(gateSource.path, "--root", root.path, "--sources"))
			addAll(roots.map { it.path })
			if (list) add("--list")
			add("--classpath")
			addAll(classpath.files.map { it.path })
		}
		val process = ProcessBuilder(command).directory(root).redirectErrorStream(true).start()
		val output = process.inputStream.bufferedReader().readText()
		if (process.waitFor() != 0) throw GradleException(output.trim())
		if (list) println(output.trim())
	}
}

tasks.named("check") { dependsOn("checkEnumSwitchGate") }
