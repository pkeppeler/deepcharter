// At most DEEPCHARTER_CLIENT_SLOTS (default 2) game clients at a time on this Mac (#255, #270). Every
// Loom client run (runClient, runPlay, runClientGameTest), however it is started (./gradlew direct,
// tools/play.sh, tools/record-evidence.sh), takes a slot in a doFirst and holds it until the build ends.
// A slot is an fcntl lock on its own file in ~/.cache/deepcharter: slot 0 is client.lock (the file PR 260's
// single-lock code uses, so old and new worktrees never exceed the slot count together), slot N is
// client.lock.N. The OS drops an fcntl lock when its process dies, so Ctrl-C, kill -9 and crashes free
// the slot; no liveness check is needed for slots. <slot file>.holder says who holds a slot, for the
// waiter's progress line. The PID is the Gradle daemon's (a human finds the client JVM with `pgrep -P <pid>`).
// Waiters are served in arrival order: each writes a ticket (queue/<millis>-<pid>-<id>.ticket) and only
// the oldest live ticket may take a free slot. A ticket whose PID is gone (kill -9) is skipped and removed.
// The ticket is removed on acquire and when a wait is interrupted. A PR 260 waiter that polls
// client.lock can still jump the queue until its worktree updates.
// Only on macOS; DEEPCHARTER_CLIENT_LOCK=1 forces it on and =0 off (tools/tests/client-lock.test.sh).
// DEEPCHARTER_CLIENT_SLOTS sets the slot count (default 2; 1 is one client at a time);
// DEEPCHARTER_LOCK_DIR moves the lock directory; DEEPCHARTER_LOCK_POLL_MS sets the retry interval;
// DEEPCHARTER_LOCK_TASKS (comma list) replaces the locked task names, for the test's stub task.
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

abstract class ClientLock : BuildService<BuildServiceParameters.None>, AutoCloseable {
	// The pool this service guards. A subclass renames it: its own slot count, lock files, queue and wording.
	protected open fun slotsEnv(): String = "DEEPCHARTER_CLIENT_SLOTS"
	protected open fun lockName(): String = "client.lock"
	protected open fun queueName(): String = "queue"
	protected open fun label(): String = "game client"

	private var dir: Path? = null
	private var channel: FileChannel? = null
	private var held: FileLock? = null
	private var holderFile: Path? = null
	private var ticket: Path? = null

	// Blocks until this build holds a slot. A second call in the same build returns at once.
	@Synchronized
	fun acquire(worktree: String, task: String) {
		if (held != null) {
			return
		}
		val lockDir = envOrNull("DEEPCHARTER_LOCK_DIR")?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".cache", "deepcharter")
		dir = lockDir
		val pollMs = (envOrNull("DEEPCHARTER_LOCK_POLL_MS") ?: "2000").toLong()
		val slots = (envOrNull(slotsEnv()) ?: "2").toInt()
		if (slots < 1) {
			throw GradleException("${slotsEnv()} must be at least 1, got $slots")
		}
		val queueDir = lockDir.resolve(queueName())
		Files.createDirectories(queueDir)
		val pid = ProcessHandle.current().pid()
		val myTicket = writeTicket(queueDir, pid)
		ticket = myTicket
		var slot = -1
		try {
			var lastNote: String? = null
			var lastNoteAt = 0L
			while (true) {
				val live = liveTickets(queueDir)
				val position = live.indexOf(myTicket) + 1
				if (position == 0) {
					throw GradleException("Queue ticket $myTicket vanished")
				}
				if (position == 1) {
					slot = tryTakeSlot(slots)
					if (slot >= 0) {
						break
					}
				}
				val note = "queue position $position of ${live.size}"
				val now = System.currentTimeMillis()
				if (note != lastNote || now - lastNoteAt >= 10_000) {
					LOG.lifecycle("Waiting for a ${label()} slot ($note). ${describeSlots(slots)} (Ctrl-C while waiting? also run ./gradlew --stop)")
					lastNote = note
					lastNoteAt = now
				}
				Thread.sleep(pollMs)
			}
			val holder = holderOf(slot)
			holderFile = holder
			Files.writeString(holder, "worktree $worktree, task $task, Gradle daemon PID $pid\n", StandardCharsets.UTF_8)
			deleteTicket()
			LOG.lifecycle("Took ${label()} slot $slot (${slotFile(slot)})")
		} catch (failed: Throwable) {
			closeChannel() // a cancelled or failed wait must not leak a channel, a held slot or a ticket
			deleteTicket()
			throw failed
		}
	}

	private fun slotFile(slot: Int): Path = dir!!.resolve(if (slot == 0) lockName() else "${lockName()}.$slot")

	private fun holderOf(slot: Int): Path {
		val file = slotFile(slot)
		return file.resolveSibling(file.fileName.toString() + ".holder")
	}

	// Takes the first free slot, or returns -1. Leaves the channel and lock in the fields on success.
	private fun tryTakeSlot(slots: Int): Int {
		for (slot in 0 until slots) {
			val candidate = FileChannel.open(slotFile(slot), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
			var lock: FileLock? = null
			try {
				lock = candidate.tryLock()
			} catch (other: OverlappingFileLockException) {
				// another build in this daemon JVM holds it
			} catch (failed: Throwable) {
				candidate.close()
				throw failed
			}
			if (lock != null) {
				channel = candidate
				held = lock
				return slot
			}
			candidate.close()
		}
		return -1
	}

	private fun describeSlots(slots: Int): String =
		(0 until slots).joinToString("; ") { slot -> "Slot $slot: ${readHolder(holderOf(slot))}" }

	private fun deleteTicket() {
		ticket?.let { Files.deleteIfExists(it) }
		ticket = null
	}

	private fun closeChannel() {
		channel?.close()
		channel = null
		held = null
	}

	@Synchronized
	override fun close() {
		try {
			deleteTicket()
		} finally {
			try {
				if (held != null) {
					Files.deleteIfExists(holderFile!!)
				}
			} finally {
				try {
					held?.release()
				} finally {
					closeChannel()
				}
			}
		}
	}

	private companion object {
		val LOG = Logging.getLogger("deepcharter.clientlock")

		// An unset or empty variable is absent.
		fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }

		fun writeTicket(queueDir: Path, pid: Long): Path {
			val now = System.currentTimeMillis()
			val id = UUID.randomUUID().toString().substring(0, 8)
			val name = String.format("%013d-%010d-%s", now, pid, id)
			val tmp = queueDir.resolve("$name.tmp")
			val file = queueDir.resolve("$name.ticket")
			Files.writeString(tmp, "$now $pid ${startOf(ProcessHandle.current())}\n", StandardCharsets.UTF_8)
			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE) // never a half-written ticket
			return file
		}

		fun startOf(process: ProcessHandle): Long = process.info().startInstant().map { it.toEpochMilli() }.orElse(0L)

		// The tickets of live processes, oldest first. Removes dead ones and leftover temp files.
		fun liveTickets(queueDir: Path): List<Path> {
			val tickets = mutableListOf<Path>()
			for (file in queueDir.toFile().listFiles() ?: emptyArray<File>()) {
				if (file.name.endsWith(".tmp")) {
					if (System.currentTimeMillis() - file.lastModified() > 60_000) {
						Files.deleteIfExists(file.toPath()) // a ticket write that died between write and rename
					}
					continue
				}
				if (!file.name.endsWith(".ticket")) {
					continue
				}
				val path = file.toPath()
				if (ticketAlive(path)) {
					tickets.add(path)
				} else {
					Files.deleteIfExists(path)
				}
			}
			tickets.sortBy { it.fileName.toString() }
			return tickets
		}

		// Alive means the PID exists and started when the ticket says: a reused PID has another start time.
		fun ticketAlive(ticket: Path): Boolean =
			try {
				val fields = Files.readString(ticket, StandardCharsets.UTF_8).trim().split(" ")
				processAlive(fields[1].toLong(), fields[2].toLong())
			} catch (unreadable: IOException) {
				false // removed under us, or not a ticket this code wrote
			} catch (unreadable: RuntimeException) {
				false
			}

		// startMillis 0 skips the start-time check (the OS did not report one).
		fun processAlive(pid: Long, startMillis: Long): Boolean {
			val process = ProcessHandle.of(pid)
			if (!process.isPresent || !process.get().isAlive) {
				return false
			}
			val actual = startOf(process.get())
			return startMillis == 0L || actual == 0L || actual == startMillis
		}

		fun readHolder(holderFile: Path): String =
			try {
				val text = Files.readString(holderFile, StandardCharsets.UTF_8).trim()
				val match = Regex("""PID (\d+)""").find(text)
				if (match != null && !processAlive(match.groupValues[1].toLong(), 0)) {
					"free or unknown" // left behind by a kill -9
				} else {
					text
				}
			} catch (unknown: IOException) {
				"free or unknown"
			}
	}
}

// The server GameTest JVM has its own pool (#541): -Xmx2G each (6G until #569), so the default of 2 at once is conservative. The default is unchanged: it was not measured with more at once.
abstract class ServerTestLock : ClientLock() {
	override fun slotsEnv(): String = "DEEPCHARTER_SERVER_TEST_SLOTS"
	override fun lockName(): String = "server-test.lock"
	override fun queueName(): String = "queue-server-test"
	override fun label(): String = "server GameTest"
}

class AcquireClientLock(private val lock: Provider<out ClientLock>, private val worktree: String) : Action<Task> {
	override fun execute(task: Task) {
		lock.get().acquire(worktree, task.name)
	}
}

val macOnByDefault = System.getProperty("os.name").lowercase().contains("mac")
val worktree = rootProject.projectDir.absolutePath

fun <T : ClientLock> lockTasks(enableEnv: String, serviceName: String, type: Class<T>, taskNames: List<String>) {
	val setting: String? = System.getenv(enableEnv)
	if (if (setting != null) setting == "1" else macOnByDefault) {
		val lock = gradle.sharedServices.registerIfAbsent(serviceName, type) {}
		tasks.matching { it.name in taskNames }.configureEach {
			usesService(lock)
			doFirst(AcquireClientLock(lock, worktree))
		}
	}
}

lockTasks(
	"DEEPCHARTER_CLIENT_LOCK",
	"clientLock",
	ClientLock::class.java,
	(System.getenv("DEEPCHARTER_LOCK_TASKS")?.takeIf { it.isNotEmpty() } ?: "runClient,runPlay,runClientGameTest").split(","),
)

// The same for the server GameTest run, in a pool of its own: DEEPCHARTER_SERVER_TEST_SLOTS (default 2) at a time on a Mac.
// DEEPCHARTER_SERVER_TEST_LOCK=1 forces it on and =0 off.
// Slot files are server-test.lock[.N] and the queue is queue-server-test, beside the client ones. tools/tests/server-test-lock.test.sh.
lockTasks("DEEPCHARTER_SERVER_TEST_LOCK", "serverTestLock", ServerTestLock::class.java, listOf("runGameTest"))
