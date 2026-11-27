import java.net.URI
import java.security.MessageDigest

// Opt-in LambDynamicLights for the dev client (#629, docs/tooling/lambdynamiclights-audit.md). Off unless DEEPCHARTER_LDL=1, and then
// only the client runs (runClient, runPlay, runClientGameTest) load it: the shipped jar, the server runs, CI and the build never see it.
//
// The jar is fetched into run/ldl (git-ignored), checked against the audited sha512 and handed to Fabric Loader with -Dfabric.addMods:
// never a Gradle dependency (audit condition 2), never committed or bundled (condition 3).
// tools/tests/test_ldl_pin.py keeps the pin below equal to the audit page.
val ldlFileName = "lambdynamiclights-4.13.0+26.3.jar"
val ldlUrl = "https://cdn.modrinth.com/data/yBW8D80W/versions/rylKnJ4M/lambdynamiclights-4.13.0%2B26.3.jar"
val ldlSha512 = "67c37ca7d619bd7f2b0dc02217070c20e34fb3bb3363d4e23f5ef9ca90b1b58adb223fab73639a3499d366497316f1eb297d2759f3ffb976467b4759f784619d"

abstract class FetchLambDynamicLights : DefaultTask() {
	@get:Input abstract val url: Property<String>
	@get:Input abstract val sha512: Property<String>
	@get:OutputFile abstract val jar: RegularFileProperty

	private fun digest(file: File): String =
		MessageDigest.getInstance("SHA-512").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

	@TaskAction
	fun fetch() {
		val target = jar.get().asFile
		if (target.isFile && digest(target) == sha512.get()) return
		target.parentFile.mkdirs()
		val partial = File(target.parentFile, target.name + ".part")
		val connection = URI(url.get()).toURL().openConnection().apply { connectTimeout = 15_000; readTimeout = 30_000 }
		connection.getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
		val actual = digest(partial)
		if (actual != sha512.get()) {
			partial.delete()
			throw GradleException("${target.name} from ${url.get()} has sha512 $actual, not the audited ${sha512.get()}; refusing to load it")
		}
		check(partial.renameTo(target)) { "could not move $partial to $target" }
	}
}

if (System.getenv("DEEPCHARTER_LDL") == "1") {
	val addVmArgToRun = project.extra["addVmArgToRun"] as (String, String) -> Unit
	val ldlJar = layout.projectDirectory.file("run/ldl/$ldlFileName")
	val fetch = tasks.register<FetchLambDynamicLights>("fetchLambDynamicLights") {
		description = "Downloads the audited LambDynamicLights jar into run/ldl and verifies its sha512 (DEEPCHARTER_LDL=1 only)."
		url = ldlUrl
		sha512 = ldlSha512
		jar = ldlJar
	}
	for ((run, task) in mapOf("client" to "runClient", "play" to "runPlay", "clientGameTest" to "runClientGameTest")) {
		addVmArgToRun(run, "-Dfabric.addMods=${ldlJar.asFile.path}")
		tasks.named(task) { dependsOn(fetch) }
	}
}
