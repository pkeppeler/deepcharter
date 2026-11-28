// N client GameTest JVMs side by side in one Gradle run, for CI (#1722). A CI runner has 4 vCPU and 16 GB, and one Minecraft client
// leaves most of it idle, so a client job runs N of them, each its own copy of the clientGameTest run:
//   -PclientInstances=N         registers the runs clientGameTest1..N and the task runClientGameTestInstances, which runs them all;
//                               with configuration cache on (gradle.properties), Gradle runs tasks of one project in parallel.
//   run dir                     build/run/clientGameTest-<k>, so options.txt, the screenshots and a dedicated server's files (and
//                               its eula.txt) are its own.
//   classes                     generateGametestModJson splits the class list into N slices (deepcharter-client-instances.txt) and
//                               the instance's ClientInstances filter keeps its own (-Ddeepcharter.clientInstance=<k>).
//   heap                        clientInstanceHeapMb below, so N heaps and their native memory fit the runner. A later -Xmx wins
//                               over the copied one, and checkRunJvmOom still finds the lone run's.
//   GC log                      client-gc.log in the run dir (the path gradle/run-jvm.gradle.kts gives is relative).
//   output                      build/client-instance-<k>.log; the action prints it with a `[client k/N]` prefix.
// The task fails when any instance does, after all of them have finished, and names each one that failed. Without the property
// nothing here is registered, so runClientGameTest, the Mac's client slots and record-evidence are as they were.
@Suppress("UNCHECKED_CAST")
val copyClientGameTestRun = project.extra["copyClientGameTestRun"] as (String, String, List<String>) -> Unit

val clientInstances = providers.gradleProperty("clientInstances").orNull?.let {
	it.toIntOrNull()?.takeIf { n -> n in 1..8 } ?: throw GradleException("-PclientInstances is '$it', expected a whole number from 1 to 8")
} ?: 1

// Each JVM's -Xmx: the 4 GB a lone client takes (gradle/run-jvm.gradle.kts) while N of them fit 10 GB of heap, leaving the rest of the
// runner's 16 GB for their native memory (llvmpipe, LWJGL), Gradle's daemon and the OS. The live set after a full collection peaked at
// 0.84 GB at 4G, and 1.07 GB at 3G (run-jvm.gradle.kts), so 2.5 GB at four instances still leaves G1 room.
fun clientInstanceHeapMb(instances: Int): Int = minOf(4096, 10240 / instances / 256 * 256)

// EXPERIMENT (#1722): -PclientInstanceCpus=c caps each instance's JVM at c processors (thread pools size from it).
val clientInstanceCpus = providers.gradleProperty("clientInstanceCpus").orNull?.toInt()

if (clientInstances > 1) {
	val heap = "-Xmx${clientInstanceHeapMb(clientInstances)}m"
	val instanceTasks = (1..clientInstances).map { k ->
		// LWJGL extracts its natives to one /tmp/lwjgl_<user>/<version> per machine: JVMs starting together raced there and loaded a
		// half-written library (FileAlreadyExistsException, or SIGABRT, exit 134, on the CI runners). Each instance extracts into its run dir.
		val runDir = layout.buildDirectory.dir("run/clientGameTest-$k").get().asFile
		copyClientGameTestRun(
			"clientGameTest$k",
			"build/run/clientGameTest-$k",
			listOf(heap, "-Ddeepcharter.clientInstance=$k", "-Dorg.lwjgl.system.SharedLibraryExtractPath=${runDir.resolve("natives").path}")
				+ listOfNotNull(clientInstanceCpus?.let { "-XX:ActiveProcessorCount=$it" }),
		)
		val task = "runClientGameTest$k"
		tasks.named<JavaExec>(task) {
			val log = layout.buildDirectory.file("client-instance-$k.log")
			// fabricApi.configureTests { eula = true } accepts the EULA in the lone run's dir only; a dedicated server a test starts
			// in this instance reads its own.
			val eula = layout.buildDirectory.file("run/clientGameTest-$k/eula.txt")
			val count = clientInstances
			// A failing instance must not stop the others mid-test: the exit code is read below, after the JVM has ended.
			isIgnoreExitValue = true
			dependsOn("generateGametestModJson")
			doFirst {
				eula.get().asFile.apply { parentFile.mkdirs() }.writeText("eula=true\n")
				val file = log.get().asFile
				file.parentFile.mkdirs()
				val out = file.outputStream()
				standardOutput = out
				errorOutput = out
			}
			doLast {
				(standardOutput as? java.io.Closeable)?.close()
				val code = executionResult.get().exitValue
				log.get().asFile.appendText("CLIENT-INSTANCE END $k/$count exit $code\n")
				if (code != 0) {
					throw GradleException("client instance $k of $count failed (exit $code): its log is build/client-instance-$k.log, prefixed [client $k/$count] in the job log")
				}
			}
		}
		task
	}
	tasks.register("runClientGameTestInstances") {
		group = "verification"
		description = "Runs the client GameTests in $clientInstances client JVMs side by side, each with its slice of the classes (#1722)."
		dependsOn(instanceTasks)
	}
}
