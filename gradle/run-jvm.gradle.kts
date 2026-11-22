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

// Server GameTest heap (#541, #805, #807, #816, #957). CI died of OutOfMemoryError at 2G with peaks of 1.23 to 1.45 GB (#805), so keep the
// heap at 2x the measured peak or more, and re-measure when the suite grows by a tenth or a test adds a tall world to a campaign environment.
// Method: -Xlog:gc plus a forced GC every 1.5 s. Mechanisms: ChunkMapUnloadMixin (chunks of a finished batch), ChunkMapSaveMixin (writes of
// half-generated chunks), FarChunks.force (released when a test finishes), and CampaignEnvironments (the campaign tests' batches).
// Measured 2026-11-22 after #818 (1454 tests, 4 vCPU, 3 runs at 3G with the campaign tests in four environments of 13 to 23): the highest live
// set was 1560, 1555 and 1748 MB, in default batches of 50 whose layer tests reach the campaign level through TestLayers; the four environments
// peaked at 1034 to 1517 MB. Main's tree (4G) peaked at 1946 MB. 2x 1748 MB is 3496 MB, so 3584m; 3G fits only a peak under 1.5 GB. Back to 3G when #1026 bounds the TestLayers batches.
val gameTestHeap = "-Xmx3584m"
addVmArgToRun("gameTest", gameTestHeap)

// Client GameTest heap, 3G (the default is a quarter of RAM). The suite's live set after a forced collection (HeapHistogram, at the start of each
// class) was 1.35 GB at the last of 69 classes (WorldLoadWaitClientTest; CI 4 vCPU, run 45062159956, passing), so 3G is 2.2 times it, and the
// live set still grows by about 20 MB a class: the dedicated servers a test starts (TwoPlayerServer, CampaignWorldClientTest) stay on the heap
// with their 6 levels (#914). Do not raise the heap for it; fix #914, and re-measure when the suite grows by a tenth. Two of these JVMs plus
// 1.5 GB of native memory each fit a 16 GB Mac, so the default two client slots stay. The run logs its collections (-Xlog:gc,
// build/client-gc.log); the client-gametest action prints the peak and the live set from it.
val clientGameTestHeap = "-Xmx3G"
addVmArgToRun("clientGameTest", clientGameTestHeap)
addVmArgToRun("clientGameTest", "-Xlog:gc:file=" + layout.buildDirectory.file("client-gc.log").get().asFile.path + ":uptime")

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
	val clientHeap = clientGameTestHeap
	doLast {
		val missing = runArgs.get().filterValues { flag !in it }.keys
		if (missing.isNotEmpty()) {
			throw GradleException("run(s) $missing lack $flag: an OutOfMemoryError there would not end the JVM, so the build could pass after one (#541)")
		}
		if (clientHeap !in runArgs.get().getValue("clientGameTest")) {
			throw GradleException("the clientGameTest run lacks $clientHeap: its heap would be a quarter of RAM, which the tall campaign world outgrows (#803)")
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
