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

// Server GameTest heap (#541, #805, #807, #816, #957, #1026). CI died of OutOfMemoryError at 2G with peaks of 1.23 to 1.45 GB (#805), so keep the
// heap at 2x the measured peak or more, and re-measure when the suite grows by a tenth, a test adds a tall world to a campaign environment, or a
// batch cap (TestBatches) rises. Method: a forced GC every 1.5 s (jcmd GC.class_histogram) over a full run. Mechanisms: ChunkMapUnloadMixin,
// ChunkMapSaveMixin, FarChunks.force, CampaignEnvironments, TestBatches.
// Measured 2026-11-22 (1479 tests, 4 vCPU, 3 runs at 3G with the caps): peaks of 1250, 1352 and 1365 MB. Batches of 50 peaked at 1.5 to 1.7 GB.
val gameTestHeap = "-Xmx3072m"
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
