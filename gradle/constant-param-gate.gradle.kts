// Constant-parameter gate (#912): a parameter of a method or constructor in src/main or src/client that every production caller passes
// the same literal is a constant in disguise. This fails the build on one; src/gametest callers are not counted.
//
// The scan is tools/ConstantParamGate.java (the JDK's class-file API, no dependency; it launches with tools/TestOnlyGate.java, whose parser it
// reuses), run over the compiled classes so a call is what javac resolved. What it exempts, and the marker `// const-param: <reason>` for a deliberate seam, are in its header.
// Every finding fails. -PconstantParamList prints the key of every finding.
// tools/tests/constant_param_gate_check.py runs it over fixture trees (tools/py tools/tests/gate_checks.py constant_param).
tasks.register("checkConstantParams") {
	group = "verification"
	description = "Fails on a mod method or constructor parameter that every production caller passes the same literal."
	val sourceSets = project.the<SourceSetContainer>()
	val mainClasses = sourceSets["main"].output.classesDirs
	val clientClasses = sourceSets["client"].output.classesDirs
	val gateSource = file("tools/ConstantParamGate.java")
	val parserSource = file("tools/TestOnlyGate.java")
	val passed = layout.buildDirectory.file("constant-param-gate/passed")
	dependsOn("classes", "clientClasses")
	inputs.files(mainClasses, clientClasses).withPropertyName("classes")
	inputs.files(sourceSets["main"].java, sourceSets["client"].java).withPropertyName("modSources")
	inputs.files(gateSource, parserSource).withPropertyName("gate")
	outputs.file(passed)
	val compileClasspath = sourceSets["client"].compileClasspath
	val sources = listOf(file("src/main/java"), file("src/client/java"))
	val root = projectDir
	val listKeys = providers.gradleProperty("constantParamList").isPresent
	inputs.property("listKeys", listKeys)
	doLast {
		val command = buildList {
			add(File(System.getProperty("java.home"), "bin/java").path)
			addAll(listOf(gateSource.path, "--root", root.path, "--production"))
			addAll((mainClasses.files + clientClasses.files).map { it.path })
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
		passed.get().asFile.apply { parentFile.mkdirs() }.writeText("ok")
	}
}

tasks.named("check") { dependsOn("checkConstantParams") }
