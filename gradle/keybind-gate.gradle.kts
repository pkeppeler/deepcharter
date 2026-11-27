// Keybind allow-list gate (#1556): playtests keep replacing a custom key with a vanilla key or a click (#1537 retired the I key, #1540 the
// B dump key), so a new key is a decision, not a default. This fails the build on a key the mod creates that gradle/keybind-allowlist.txt does
// not list. Each line there is `key.deepcharter.<id>  <reason: why no vanilla key or click fits>`; `#` starts a comment line. The reason is
// at least three words and is not judged by the gate: read it, and reject "no vanilla key" with nothing behind it.
//
// The scan is source text over src/main/java and src/client/java, with comments blanked. It checks, in this order:
//   - every `new KeyMapping(` reads its id from a string literal, a literal prefix (`"key.deepcharter.catalogue." + name`) or a
//     `String NAME = "..."` constant in the scanned sources, and the id is listed (a prefix needs at least one entry under it)
//   - a file with `KeyMappingHelper.registerKeyMapping(` also has a `new KeyMapping(`, so the id stays readable in one file
//   - every `key.deepcharter.*` key in src/lang/en_us/*.json is listed: a key built from a prefix is seen there, since a key with no
//     name in the Controls screen is a bug anyway (a `key.category.*` entry is not a key)
//   - every entry is used by a key the scan found, so a retired key leaves the list with its code
// Not seen, the price of no type information: a key built through a factory in another file, or an id assembled at runtime.
//
// tools/tests/keybind_gate_check.py runs the scan over fixture trees (checkGametestFixtures in gametest.gradle.kts).
object KeybindGate {
	private val CREATE = Regex("""\bnew\s+KeyMapping\s*\(""")
	private val REGISTER = Regex("""\bKeyMappingHelper\s*\.\s*registerKeyMapping\s*\(""")
	private val CONSTANT = Regex("""\bString\s+(\w+)\s*=\s*"([^"\\]*)"\s*;""")
	private val NAME = Regex("""^(?:(?:\w+\s*\.\s*)*(\w+)\s*\.\s*)?(\w+)$""")
	private val LANG_KEY = Regex(""""(key\.deepcharter\.[^"]+)"\s*:""")
	val ID = Regex("""^key\.deepcharter\.[a-z0-9_.]+$""")
	const val MIN_REASON_WORDS = 3

	/** {@code text} with every comment turned into spaces, newlines kept, string literals left. */
	fun withoutComments(text: String): String {
		val out = StringBuilder(text)
		var i = 0
		while (i < text.length) {
			when {
				text[i] == '"' -> {
					i++
					while (i < text.length && text[i] != '"' && text[i] != '\n') i += if (text[i] == '\\') 2 else 1
					i++
				}
				text.startsWith("//", i) || text.startsWith("/*", i) -> {
					val end = if (text.startsWith("//", i)) text.indexOf('\n', i).let { if (it < 0) text.length else it }
					else text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
					for (k in i until end) if (text[k] != '\n') out[k] = ' '
					i = end
				}
				else -> i++
			}
		}
		return out.toString()
	}

	private fun lineOf(text: String, offset: Int) = text.substring(0, offset).count { it == '\n' } + 1

	/** The text of the first argument of the call whose `(` ends just before {@code from}. */
	private fun firstArgument(code: String, from: Int): String {
		var depth = 0
		var i = from
		while (i < code.length) {
			val c = code[i]
			when {
				c == '"' -> {
					i++
					while (i < code.length && code[i] != '"') i += if (code[i] == '\\') 2 else 1
				}
				c == '(' || c == '[' || c == '{' -> depth++
				(c == ')' || c == ']' || c == '}') && depth == 0 -> return code.substring(from, i).trim()
				c == ')' || c == ']' || c == '}' -> depth--
				c == ',' && depth == 0 -> return code.substring(from, i).trim()
			}
			i++
		}
		return code.substring(from).trim()
	}

	/** id -> reason from the allow-list text, and the problems found reading it (as "line message"). */
	fun parseAllowList(text: String): Pair<Map<String, String>, List<Pair<Int, String>>> {
		val entries = linkedMapOf<String, String>()
		val problems = mutableListOf<Pair<Int, String>>()
		text.lines().forEachIndexed { index, raw ->
			val line = raw.trim()
			if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
			val id = line.split(Regex("\\s+"), 2)[0]
			val reason = line.removePrefix(id).trim()
			when {
				!ID.matches(id) -> problems.add(index + 1 to "'$id' is not a key id: lines are 'key.deepcharter.<id>  <reason>'")
				reason.split(Regex("\\s+")).filter { it.isNotEmpty() }.size < MIN_REASON_WORDS ->
					problems.add(index + 1 to "'$id' needs a reason of at least $MIN_REASON_WORDS words: why no vanilla key or click fits")
				entries.containsKey(id) -> problems.add(index + 1 to "'$id' is listed twice")
				else -> entries[id] = reason
			}
		}
		return entries to problems
	}

	/** The problems (path:line message) over {@code sources}, {@code allowlist} and the lang fragments, paths relative to {@code base}. */
	fun problems(sources: List<File>, allowlist: File, langFiles: List<File>, base: File): List<String> {
		val found = mutableListOf<String>()
		fun rel(f: File) = f.relativeTo(base).path
		val (entries, entryProblems) = parseAllowList(allowlist.readText())
		entryProblems.forEach { (n, message) -> found.add("${rel(allowlist)}:$n $message") }
		val codes = sources.sorted().associateWith { withoutComments(it.readText()) }
		// A constant is "File.NAME": a bare name reads the file's own, a qualified one the file named by its last qualifier.
		val constants = mutableMapOf<String, MutableSet<String>>()
		codes.forEach { (source, code) ->
			CONSTANT.findAll(code).forEach { constants.getOrPut("${source.nameWithoutExtension}.${it.groupValues[1]}") { mutableSetOf() }.add(it.groupValues[2]) }
		}
		val used = mutableSetOf<String>()
		for ((source, code) in codes) {
			val creates = CREATE.findAll(code).toList()
			for (call in creates) {
				val n = lineOf(code, call.range.first)
				val arg = firstArgument(code, call.range.last + 1)
				val exact = mutableListOf<String>()
				var prefix: String? = null
				if (arg.startsWith("\"")) {
					val end = arg.indexOf('"', 1)
					val literal = if (end < 0) arg.substring(1) else arg.substring(1, end)
					val rest = if (end < 0) "" else arg.substring(end + 1).trim()
					if (rest.isEmpty()) exact.add(literal) else if (rest.startsWith("+")) prefix = literal
				} else {
					NAME.matchEntire(arg)?.let { name ->
						constants["${name.groupValues[1].ifEmpty { source.nameWithoutExtension }}.${name.groupValues[2]}"]?.let { exact.addAll(it) }
					}
				}
				if (exact.isEmpty() && prefix == null) {
					found.add("${rel(source)}:$n cannot read the key id from 'new KeyMapping($arg': pass a string literal, a literal prefix + name, or a String constant")
					continue
				}
				exact.forEach { id ->
					used.add(id)
					if (id !in entries) found.add("${rel(source)}:$n key '$id' has no entry in ${rel(allowlist)}")
				}
				prefix?.let { p ->
					val under = entries.keys.filter { it.startsWith(p) }
					used.addAll(under)
					if (under.isEmpty()) found.add("${rel(source)}:$n keys named from '$p' have no entry in ${rel(allowlist)}")
				}
			}
			REGISTER.findAll(code).firstOrNull()?.let { call ->
				if (creates.isEmpty()) found.add("${rel(source)}:${lineOf(code, call.range.first)} registers a key this file does not create: build it here so its id is readable")
			}
		}
		langFiles.sorted().forEach { lang ->
			val text = lang.readText()
			LANG_KEY.findAll(text).forEach { match ->
				val id = match.groupValues[1]
				if (id !in entries) found.add("${rel(lang)}:${lineOf(text, match.range.first)} key '$id' has no entry in ${rel(allowlist)}")
				used.add(id)
			}
		}
		entries.keys.filter { it !in used }.forEach { found.add("${rel(allowlist)}: entry '$it' matches no key in the sources or the lang files: delete it") }
		return found
	}
}

val keybindAdvice = "A custom key is a last resort: a vanilla key or a click is what players already know, and playtests keep replacing the custom " +
	"ones. Look for a vanilla binding or a click first. If none fits, add 'key.deepcharter.<id>  <why no vanilla key or click fits>' " +
	"to gradle/keybind-allowlist.txt, and say why in the PR; a retired key leaves the list."

/** The problems over the fixture tree {@code root}: its .java files, the one keybind-allowlist.txt and its .json lang fragments. */
val keybindProblems: (File, File) -> List<String> = { root, base ->
	val files = root.walkTopDown().filter { it.isFile }.toList()
	val allowlist = files.singleOrNull { it.name == "keybind-allowlist.txt" }
		?: throw GradleException("the fixture tree needs exactly one keybind-allowlist.txt")
	KeybindGate.problems(files.filter { it.name.endsWith(".java") }, allowlist, files.filter { it.name.endsWith(".json") }, base)
}
// Shared with checkGametestFixtures (gametest.gradle.kts), as orphan-comment-gate.gradle.kts does.
extra["keybindProblems"] = keybindProblems
extra["keybindAdvice"] = keybindAdvice

tasks.register("checkKeybindGate") {
	group = "verification"
	description = "Fails on a custom key that gradle/keybind-allowlist.txt does not list with a reason."
	val root = projectDir
	val allowlist = file("gradle/keybind-allowlist.txt")
	val sources = fileTree(root) { include("src/main/java/**/*.java", "src/client/java/**/*.java") }
	val lang = fileTree(file("src/lang/en_us")) { include("*.json") }
	val advice = keybindAdvice
	inputs.files(sources).withPropertyName("sources")
	inputs.files(lang).withPropertyName("lang")
	inputs.file(allowlist).withPropertyName("allowlist")
	doLast {
		val problems = KeybindGate.problems(sources.files.toList(), allowlist, lang.files.toList(), root)
		if (problems.isNotEmpty()) {
			throw GradleException("Custom keys without an allow-list entry. $advice\n  " + problems.joinToString("\n  "))
		}
	}
}

tasks.named("check") { dependsOn("checkKeybindGate") }
