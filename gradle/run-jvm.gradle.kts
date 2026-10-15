// JVM args of every Loom run (the server and client GameTests, the play-test client): an OutOfMemoryError must end the JVM
// at once with a non-zero exit, so the Gradle task fails (#541). Without the flag a harness or the server's crash handler
// catches the error and the JVM exits 0 (build passed after an OOM) or hangs on its other threads (23 min and 1 h).
val exitOnOom = "-XX:+ExitOnOutOfMemoryError"

// The Loom runs, as the functions build.gradle.kts offers (applied scripts cannot see the Loom plugin's types).
val addVmArgToRuns = project.extra["addVmArgToRuns"] as (String) -> Unit
val addVmArgToRun = project.extra["addVmArgToRun"] as (String, String) -> Unit
@Suppress("UNCHECKED_CAST")
val runVmArgs = project.extra["runVmArgs"] as () -> Map<String, List<String>>

addVmArgToRuns(exitOnOom)

// Server GameTest heap. No -Xmx was set, so the JVM took a quarter of RAM: 4 GB on the 16 GB Mac, and the suite (1050 tests) ran
// out of it at about 80% (#541). Two leaks filled it. GameTestHelper.onEachTick queued one lambda per remaining tick (#548;
// TestTicks.each replaced it). Chunks that FarChunks forced stayed forced to the end of the run, and each forced chunk pins the
// proto chunks around it, so one layer level held 34,000 chunks (#569; FarChunks.force lets go when the test finishes).
// Measured 2026-10-15 (-Xlog:gc, 1110 tests): 1 GB fails (Full GCs at 1022M), 1.5 GB passes with 6 Full GCs, 2 GB passes with none.
// The live set (class histogram, which forces a full GC) is 300 to 550 MB for most of the run and peaks at 1.13 GB while a batch
// of 50 tests is running (about 7,000 chunks of the layer levels, 370,000 chunk sections, and the lighting arrays), then falls
// back to 550 MB when the batch ends. It is a working set, not a leak. PoiManager is not the holder (#636): at the peak it held
// 920 PoiSections. Vanilla's SectionStorage never removes a section from its map (flush only writes it), so the key index grows
// with every chunk ever loaded, but an unsaved section is a shared Optional.empty, so 640,000 entries cost about 20 MB.
// Twice the peak is 2.3 GB, so 2G is already the floor. Re-measure when the suite grows by a tenth.
val gameTestHeap = "-Xmx2G"
addVmArgToRun("gameTest", gameTestHeap)

// Proof the gate holds: every run carries the flag, and a JVM that catches its OutOfMemoryError exits non-zero with the flag and
// 0 without it. The second half fails if the JVM ever stops honouring the flag; the first, if a run drops it.
val probeDir = layout.buildDirectory.dir("oom-probe")
val probeSource = layout.projectDirectory.file("gradle/OomProbe.java")
val javaBin = File(System.getProperty("java.home"), "bin/java").path

val probeTasks = mapOf("flagged" to listOf(exitOnOom), "bare" to emptyList<String>()).map { (name, flags) ->
	tasks.register<Exec>("oomProbe${name.replaceFirstChar { it.uppercase() }}") {
		inputs.file(probeSource)
		outputs.file(probeDir.map { it.file("$name.exit") })
		isIgnoreExitValue = true
		commandLine(listOf(javaBin, "-Xmx16m") + flags + listOf(probeSource.asFile.path))
		val captured = probeDir.map { it.file("$name.log") }
		val logDir = probeDir
		doFirst {
			logDir.get().asFile.mkdirs()
			standardOutput = captured.get().asFile.outputStream()
			errorOutput = standardOutput
		}
		val exitFile = probeDir.map { it.file("$name.exit") }
		doLast {
			exitFile.get().asFile.writeText("${executionResult.get().exitValue}")
		}
	}
}

tasks.register("checkRunJvmOom") {
	group = "verification"
	description = "Fails unless every Loom run exits on OutOfMemoryError and a JVM given that flag does exit non-zero."
	dependsOn(probeTasks)
	val runArgs = providers.provider { runVmArgs() }
	inputs.property("runArgs", runArgs)
	val flagged = probeDir.map { it.file("flagged.exit") }
	val bare = probeDir.map { it.file("bare.exit") }
	val flag = exitOnOom
	val heap = gameTestHeap
	doLast {
		val missing = runArgs.get().filterValues { flag !in it }.keys
		if (missing.isNotEmpty()) {
			throw GradleException("run(s) $missing lack $flag: an OutOfMemoryError there would not end the JVM, so the build could pass after one (#541)")
		}
		if (heap !in runArgs.get().getValue("gameTest")) {
			throw GradleException("the gameTest run lacks $heap: its heap would be the JVM default (a quarter of RAM), which the suite outgrows (#541)")
		}
		val withFlag = flagged.get().asFile.readText().trim().toInt()
		val without = bare.get().asFile.readText().trim().toInt()
		if (withFlag == 0) {
			throw GradleException("a JVM run with $flag exited 0 after a caught OutOfMemoryError; the flag no longer fails the run")
		}
		if (without != 0) {
			throw GradleException("the control JVM (no flag) exited $without, not 0; the probe no longer shows the silent exit 0 the flag exists to prevent")
		}
	}
}
tasks.named("check") { dependsOn("checkRunJvmOom") }
