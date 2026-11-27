// Vanilla sound gate (#1557): playtests flagged vanilla sounds that break the tone: the vanilla explosion on a breach (#1293) and vanilla
// music in the unbuilt depth (#1296). This fails the build on a reference to a sound on the banned list, gradle/banned-vanilla-sounds.txt,
// in two places:
//   - Java in src/main/java and src/client/java, unless the line carries `// vanilla-ok: <reason>` (the reason is required) on a line of
//     the statement or the comment lines above it (`markedNear`);
//   - a sound id in a JSON data file under src/main/resources (sounds.json, a dimension type, a biome), unless
//     gradle/vanilla-sound-allowlist.txt lists the file and the id with a reason: `<path>  <id>  <why this one stays>`.
// Prefer a mod sound (`DeepSound`) to either escape.
//
// The list is one data file, extendable without touching this script. Each line is `<token>  <why it breaks the tone>`; `#` starts a comment
// line. A token that starts with `minecraft:` is a sound id, matched against each quoted string of a JSON file (`*` is any run of name
// characters, dots and slashes: `minecraft:music.*`). Any other token is Java, matched in the code of a line with comments and string
// literals blanked (`SoundEvents.GENERIC_EXPLODE`, `.explode(`); there `*` stands for any run of name characters (`SoundEvents.MUSIC_BIOME_*`).
// A Java token that starts or ends with a name character must not touch another one, so `Musics.GAME` does not match `Musics.GAME_2`.
//
// A line scan with no type information. Not seen: a sound reached through a variable or a static import's bare name, a `SoundEvents` name
// split across lines, an id built in Java, and a data file outside src/main/resources (the GameTest resources keep their own copies of the
// dimension types).
//
// tools/tests/vanilla_sound_gate_check.py runs the scan over fixture trees (checkGametestFixtures in gametest.gradle.kts).
object VanillaSoundGate {
	private val MARKER = Regex("""//\s*vanilla-ok:\s*\S""")
	private val QUOTED = Regex(""""([^"\\\n]+)"""")
	const val MIN_REASON_WORDS = 3
	const val ID_PREFIX = "minecraft:"

	/** The banned tokens of the list text: Java tokens and sound ids, each with its regex, and the problems found reading it ("line message"). */
	class Banned(val java: List<Pair<String, Regex>>, val ids: List<Pair<String, Regex>>)

	fun parseList(text: String): Pair<Banned, List<Pair<Int, String>>> {
		val java = mutableListOf<Pair<String, Regex>>()
		val ids = mutableListOf<Pair<String, Regex>>()
		val problems = mutableListOf<Pair<Int, String>>()
		text.lines().forEachIndexed { index, raw ->
			val line = raw.trim()
			if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
			val token = line.split(Regex("\\s+"), 2)[0]
			val reason = line.removePrefix(token).trim()
			if (token.replace("*", "").isEmpty()) {
				problems.add(index + 1 to "'$token' matches everything: name the sound")
				return@forEachIndexed
			}
			if (reason.split(Regex("\\s+")).filter { it.isNotEmpty() }.size < MIN_REASON_WORDS) {
				problems.add(index + 1 to "'$token' needs a reason of at least $MIN_REASON_WORDS words: why it breaks the tone")
				return@forEachIndexed
			}
			if (token.startsWith(ID_PREFIX)) {
				ids.add(token to Regex(token.split("*").joinToString("[\\w./]*") { Regex.escape(it) }))
				return@forEachIndexed
			}
			val body = token.split("*").joinToString("\\w*") { Regex.escape(it) }
			val before = if (token.first().let { it.isLetterOrDigit() || it == '_' }) "(?<!\\w)" else ""
			val after = if (token.last().let { it.isLetterOrDigit() || it == '_' }) "(?!\\w)" else ""
			java.add(token to Regex(before + body + after))
		}
		return Banned(java, ids) to problems
	}

	/** (1-based line, token) of each banned reference in {@code text} with no marker; one per line, the first token on the list that matches. */
	fun violations(
		text: String,
		patterns: List<Pair<String, Regex>>,
		blankLiterals: (String) -> String,
		codeLines: (List<String>) -> List<String>,
		markedNear: (List<String>, Int, (String) -> Boolean) -> Boolean,
	): List<Pair<Int, String>> {
		val lines = blankLiterals(text).lines()
		val codes = codeLines(lines)
		val found = mutableListOf<Pair<Int, String>>()
		codes.forEachIndexed { index, code ->
			val hit = patterns.firstOrNull { (_, pattern) -> pattern.containsMatchIn(code) } ?: return@forEachIndexed
			if (!markedNear(lines, index) { MARKER.containsMatchIn(it) }) found.add(index + 1 to hit.first)
		}
		return found
	}

	/** (1-based line, id, token) of each quoted string of the JSON {@code text} that is a banned sound id. */
	fun idsIn(text: String, patterns: List<Pair<String, Regex>>): List<Triple<Int, String, String>> {
		val found = mutableListOf<Triple<Int, String, String>>()
		text.lines().forEachIndexed { index, line ->
			QUOTED.findAll(line).forEach { quoted ->
				val id = quoted.groupValues[1]
				patterns.firstOrNull { (_, pattern) -> pattern.matches(id) }?.let { found.add(Triple(index + 1, id, it.first)) }
			}
		}
		return found
	}

	/** The problems (path:line message) in {@code sources} and the {@code data} files against the list, paths relative to {@code base}. */
	fun problems(
		sources: List<File>,
		data: List<File>,
		list: File,
		allowlist: File?,
		base: File,
		blankLiterals: (String) -> String,
		codeLines: (List<String>) -> List<String>,
		markedNear: (List<String>, Int, (String) -> Boolean) -> Boolean,
	): List<String> {
		val found = mutableListOf<String>()
		fun rel(f: File) = f.relativeTo(base).path.replace(File.separatorChar, '/')
		val (banned, listProblems) = parseList(list.readText())
		listProblems.forEach { (n, message) -> found.add("${rel(list)}:$n $message") }
		sources.sorted().forEach { source ->
			val lines = source.reader().readLines()
			violations(source.readText(), banned.java, blankLiterals, codeLines, markedNear).forEach { (n, token) ->
				found.add("${rel(source)}:$n banned vanilla sound '$token': ${lines[n - 1].trim()}")
			}
		}
		// The allow-list: `<path>  <id>  <reason>`, one per line; an entry that allows nothing is stale.
		val allowed = linkedMapOf<Pair<String, String>, Int>()
		allowlist?.takeIf { it.isFile }?.readText()?.lines()?.forEachIndexed { index, raw ->
			val line = raw.trim()
			if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
			val parts = line.split(Regex("\\s+"), 3)
			val reason = parts.getOrElse(2) { "" }
			if (parts.size < 3 || reason.split(Regex("\\s+")).size < MIN_REASON_WORDS) {
				found.add("${rel(allowlist)}:${index + 1} '${parts[0]}' '${parts.getOrElse(1) { "" }}' needs a reason of at least $MIN_REASON_WORDS words: why this sound id stays")
			} else {
				allowed[parts[0] to parts[1]] = index + 1
			}
		}
		val used = mutableSetOf<Pair<String, String>>()
		data.sorted().forEach { file ->
			val lines = file.reader().readLines()
			idsIn(file.readText(), banned.ids).forEach { (n, id, token) ->
				val key = rel(file) to id
				if (key in allowed) {
					used.add(key)
				} else {
					found.add("${rel(file)}:$n banned vanilla sound id '$id' ($token): ${lines[n - 1].trim()}")
				}
			}
		}
		allowed.filterKeys { it !in used }.forEach { (key, n) ->
			found.add("${rel(allowlist!!)}:$n entry '${key.first}' '${key.second}' matches no banned id: delete it")
		}
		return found
	}
}

val vanillaSoundAdvice = "A vanilla sound on this list breaks the tone (the explosion on a breach, music in the unbuilt depth). Use a mod sound " +
	"(DeepSound) or make one in tools/sounds. A Java line that must keep the vanilla sound ends with '// vanilla-ok: <reason>', or has it on " +
	"the contiguous // lines above its statement; a sound id in a JSON data file is listed in gradle/vanilla-sound-allowlist.txt as " +
	"'<path>  <id>  <reason>'. The list is gradle/banned-vanilla-sounds.txt: add a line to ban another."

/** The problems over the fixture tree {@code root}: its .java and .json files, the one banned-vanilla-sounds.txt and an optional vanilla-sound-allowlist.txt. */
val vanillaSoundProblems: (File, File, (String) -> String, (List<String>) -> List<String>, (List<String>, Int, (String) -> Boolean) -> Boolean) -> List<String> =
	{ root, base, blankLiterals, codeLines, markedNear ->
		val files = root.walkTopDown().filter { it.isFile }.toList()
		val list = files.singleOrNull { it.name == "banned-vanilla-sounds.txt" }
			?: throw GradleException("the fixture tree needs exactly one banned-vanilla-sounds.txt")
		VanillaSoundGate.problems(
			files.filter { it.name.endsWith(".java") }, files.filter { it.name.endsWith(".json") }, list,
			files.singleOrNull { it.name == "vanilla-sound-allowlist.txt" }, base, blankLiterals, codeLines, markedNear,
		)
	}
// Shared with checkGametestFixtures (gametest.gradle.kts), as keybind-gate.gradle.kts does.
extra["vanillaSoundProblems"] = vanillaSoundProblems
extra["vanillaSoundAdvice"] = vanillaSoundAdvice

tasks.register("checkVanillaSoundGate") {
	group = "verification"
	description = "Fails on a banned vanilla sound or music in src/main or src/client Java with no '// vanilla-ok: <reason>' marker, or in src/main JSON data not allow-listed."
	@Suppress("UNCHECKED_CAST")
	val blankLiterals = project.extra["blankLiterals"] as (String) -> String
	@Suppress("UNCHECKED_CAST")
	val codeLines = project.extra["codeLines"] as (List<String>) -> List<String>
	@Suppress("UNCHECKED_CAST")
	val markedNear = project.extra["markedNear"] as (List<String>, Int, (String) -> Boolean) -> Boolean
	val root = projectDir
	val list = file("gradle/banned-vanilla-sounds.txt")
	val allowlist = file("gradle/vanilla-sound-allowlist.txt")
	val sources = fileTree(root) { include("src/main/java/**/*.java", "src/client/java/**/*.java") }
	val data = fileTree(root) { include("src/main/resources/**/*.json") }
	val advice = vanillaSoundAdvice
	inputs.files(sources).withPropertyName("sources")
	inputs.files(data).withPropertyName("data")
	inputs.file(list).withPropertyName("list")
	inputs.file(allowlist).withPropertyName("allowlist")
	doLast {
		val problems = VanillaSoundGate.problems(sources.files.toList(), data.files.toList(), list, allowlist, root, blankLiterals, codeLines, markedNear)
		if (problems.isNotEmpty()) {
			throw GradleException("Banned vanilla sounds. $advice\n  " + problems.joinToString("\n  "))
		}
	}
}

tasks.named("check") { dependsOn("checkVanillaSoundGate") }
