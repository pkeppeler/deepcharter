// Test-only gate (#461): a test is not a production caller, so a non-private method, constructor or field in src/main or src/client that
// only src/gametest references is dead weight in the mod, or an accessor widened for a test. This fails the build on one.
//
// The scan is tools/TestOnlyGate.java (the JDK's class-file API, no dependency), run over the compiled classes so a reference is what javac
// resolved. What it exempts, and the marker `// test-seam: <reason>` for a deliberate seam, are in its header.
// Every finding fails. -PtestOnlyList prints the key of every finding.
// tools/tests/testonly_gate_check.py runs it over fixture trees (tools/py tools/tests/gate_checks.py testonly).
tasks.register("checkTestOnlyMembers") {
	group = "verification"
	description = "Fails on a non-private mod member that only src/gametest references."
	val sourceSets = project.the<SourceSetContainer>()
	val mainClasses = sourceSets["main"].output.classesDirs
	val clientClasses = sourceSets["client"].output.classesDirs
	val gametestClasses = sourceSets["gametest"].output.classesDirs
	val gateSource = file("tools/TestOnlyGate.java")
	dependsOn("classes", "clientClasses", "gametestClasses")
	inputs.files(mainClasses, clientClasses, gametestClasses).withPropertyName("classes")
	inputs.files(sourceSets["main"].java, sourceSets["client"].java).withPropertyName("modSources")
	inputs.file(gateSource).withPropertyName("gate")
	val compileClasspath = sourceSets["gametest"].compileClasspath
	val sources = listOf(file("src/main/java"), file("src/client/java"))
	val root = projectDir
	val listKeys = providers.gradleProperty("testOnlyList").isPresent
	inputs.property("listKeys", listKeys)
	doLast {
		val command = buildList {
			add(File(System.getProperty("java.home"), "bin/java").path)
			addAll(listOf(gateSource.path, "--root", root.path, "--production"))
			addAll((mainClasses.files + clientClasses.files).map { it.path })
			add("--tests")
			addAll(gametestClasses.files.map { it.path })
			add("--sources")
			addAll(sources.map { it.path })
			if (listKeys) add("--list")
			add("--classpath")
			addAll(compileClasspath.files.map { it.path })
		}
		val process = ProcessBuilder(command).directory(root).redirectErrorStream(true).start()
		val output = process.inputStream.bufferedReader().readText()
		if (process.waitFor() != 0) throw GradleException(output.trim())
		if (listKeys) println(output.trim())
	}
}

tasks.named("check") { dependsOn("checkTestOnlyMembers") }
