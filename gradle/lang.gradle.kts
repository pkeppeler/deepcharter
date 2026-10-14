import groovy.json.JsonSlurper
import java.util.TreeMap
import java.util.zip.ZipFile

// Lang files are generated: each feature owns src/lang/<locale>/<feature>.json.
// A key may appear in exactly one fragment; any duplicate fails the build.
object LangMerge {
	fun merge(parts: Map<String, File>): Map<String, String> {
		val owner = mutableMapOf<String, String>()
		val merged = TreeMap<String, String>()
		parts.forEach { (name, file) ->
			val json = JsonSlurper().parse(file, "UTF-8") as Map<*, *>
			json.forEach { (rawKey, v) ->
				val k = rawKey as String
				if (v !is String) throw GradleException("Lang value for '$k' in $name is not a string")
				if (owner.containsKey(k)) {
					throw GradleException("Duplicate lang key '$k' in ${owner[k]} and $name: one owner per key")
				}
				owner[k] = name
				merged[k] = v
			}
		}
		return merged
	}

	// Flat string map, UTF-8 literal characters; only quote, backslash and control characters are escaped.
	fun write(map: Map<String, String>): String {
		fun esc(s: String): String {
			val sb = StringBuilder()
			for (c in s) {
				when {
					c == '"' -> sb.append("\\\"")
					c == '\\' -> sb.append("\\\\")
					c == '\n' -> sb.append("\\n")
					c == '\r' -> sb.append("\\r")
					c == '\t' -> sb.append("\\t")
					c < ' ' -> sb.append(String.format("\\u%04x", c.code))
					else -> sb.append(c)
				}
			}
			return sb.toString()
		}
		return "{\n" + map.entries.joinToString(",\n") { (k, v) -> "  \"${esc(k)}\": \"${esc(v)}\"" } + "\n}\n"
	}
}

abstract class MergeLang : DefaultTask() {
	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val partsDir: DirectoryProperty

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	@TaskAction
	fun run() {
		val root = partsDir.get().asFile
		val out = outputDir.get().asFile
		out.deleteRecursively()
		val locales = root.listFiles { f: File -> f.isDirectory }
		if (locales == null || locales.isEmpty()) throw GradleException("No locale directories in $root")
		locales.sorted().forEach { locale ->
			val files = locale.listFiles { f: File -> f.name.endsWith(".json") }
			if (files == null || files.isEmpty()) throw GradleException("No lang fragments in $locale")
			val parts = TreeMap<String, File>(files.associateBy { it.name })
			val target = File(out, "assets/deepcharter/lang/${locale.name}.json")
			target.parentFile.mkdirs()
			target.writeText(LangMerge.write(LangMerge.merge(parts)), Charsets.UTF_8)
		}
	}
}

val mergeLang = tasks.register<MergeLang>("mergeLang") {
	partsDir = layout.projectDirectory.dir("src/lang")
	outputDir = layout.buildDirectory.dir("generated/lang")
}

tasks.named<ProcessResources>("processResources") { from(mergeLang) }

// Build-time checks, run in `check`: duplicates fail, sorted output, literal UTF-8, jar contents.
val checkLang = tasks.register("checkLang") {
	dependsOn(mergeLang, tasks.named("jar"))
	val generated = mergeLang.flatMap { it.outputDir.file("assets/deepcharter/lang/en_us.json") }
	val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
	val tmp = layout.buildDirectory.dir("tmp/checkLang")
	inputs.file(generated)
	inputs.file(jarFile)
	outputs.dir(tmp)
	doLast {
		val dir = tmp.get().asFile
		dir.deleteRecursively()
		dir.mkdirs()
		val a = File(dir, "a.json")
		a.writeText("""{"x.k": "1"}""", Charsets.UTF_8)
		val b = File(dir, "b.json")
		b.writeText("""{"x.k": "1"}""", Charsets.UTF_8)
		var rejected = false
		try {
			LangMerge.merge(mapOf("a" to a, "b" to b))
		} catch (e: GradleException) {
			rejected = e.message!!.startsWith("Duplicate lang key 'x.k'")
		}
		if (!rejected) throw GradleException("Duplicate lang key was not rejected")

		val keys = (JsonSlurper().parse(generated.get().asFile, "UTF-8") as Map<*, *>).keys.map { it as String }
		if (keys != keys.sorted()) throw GradleException("Generated en_us.json is not sorted")

		val u = File(dir, "u.json")
		u.writeText("""{"x.u": "a — b é \"q\""}""", Charsets.UTF_8)
		val text = LangMerge.write(LangMerge.merge(mapOf("u" to u)))
		if (!text.contains("a — b é \\\"q\\\"")) throw GradleException("Non-ASCII characters were not written literally: $text")
		if ((JsonSlurper().parseText(text) as Map<*, *>)["x.u"] != "a — b é \"q\"") throw GradleException("Non-ASCII output does not round-trip")

		val names = mutableListOf<String>()
		ZipFile(jarFile.get().asFile).use { z -> z.entries().asSequence().forEach { names.add(it.name) } }
		if (!names.contains("assets/deepcharter/lang/en_us.json")) throw GradleException("Jar lacks assets/deepcharter/lang/en_us.json")
		val leaked = names.filter { it.startsWith("src/lang") || it.startsWith("lang/") }
		if (leaked.isNotEmpty()) throw GradleException("Lang fragments leaked into the jar: $leaked")
	}
}
tasks.named("check") { dependsOn(checkLang) }
