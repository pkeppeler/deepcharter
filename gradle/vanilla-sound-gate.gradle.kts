// Vanilla sound gate (#1557): playtests flagged vanilla sounds that break the tone: the vanilla explosion on a breach (#1293) and vanilla
// music in the unbuilt depth (#1296). This fails the build on a reference in src/main/java or src/client/java to a sound on the banned list,
// gradle/banned-vanilla-sounds.txt, unless the line carries `// vanilla-ok: <reason>` (the reason is required) on a line of the
// statement or the comment lines above it (`markedNear`). Prefer a mod sound (`DeepSound`) over a marker.
//
// The list is one data file, extendable without touching this script. Each line is `<token>  <why it breaks the tone>`; `#` starts a comment
// line. A token is matched in the code of a line, comments and string literals blanked, and is a field or method as written there
// (`SoundEvents.GENERIC_EXPLODE`, `.explode(`); `*` stands for any run of name characters (`SoundEvents.MUSIC_*`). A token that starts or
// ends with a name character must not touch another one, so `Musics.GAME` does not match `Musics.GAME_2`.
//
// A line scan with no type information. Not seen: a sound reached through a variable or a static import's bare name, a `SoundEvents` name
// split across lines, and an id in a data file (a biome's music or ambient sound), which this scan does not read.
//
// tools/tests/vanilla_sound_gate_check.py runs the scan over fixture trees (checkGametestFixtures in gametest.gradle.kts).
object VanillaSoundGate {
	private val MARKER = Regex("""//\s*vanilla-ok:\s*\S""")
	const val MIN_REASON_WORDS = 3

	/** The patterns of the list text, token to regex, and the problems found reading it ("line message"). */
	fun parseList(text: String): Pair<List<Pair<String, Regex>>, List<Pair<Int, String>>> {
		val patterns = mutableListOf<Pair<String, Regex>>()
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
			val body = token.split("*").joinToString("\\w*") { Regex.escape(it) }
			val before = if (token.first().let { it.isLetterOrDigit() || it == '_' }) "(?<!\\w)" else ""
			val after = if (token.last().let { it.isLetterOrDigit() || it == '_' }) "(?!\\w)" else ""
			patterns.add(token to Regex(before + body + after))
		}
		return patterns to problems
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

	/** The problems (path:line message) in {@code sources} against the list file, paths relative to {@code base}. */
	fun problems(
		sources: List<File>,
		list: File,
		base: File,
		blankLiterals: (String) -> String,
		codeLines: (List<String>) -> List<String>,
		markedNear: (List<String>, Int, (String) -> Boolean) -> Boolean,
	): List<String> {
		val found = mutableListOf<String>()
		val (patterns, listProblems) = parseList(list.readText())
		listProblems.forEach { (n, message) -> found.add("${list.relativeTo(base).path}:$n $message") }
		sources.sorted().forEach { source ->
			val lines = source.reader().readLines()
			violations(source.readText(), patterns, blankLiterals, codeLines, markedNear).forEach { (n, token) ->
				found.add("${source.relativeTo(base).path}:$n banned vanilla sound '$token': ${lines[n - 1].trim()}")
			}
		}
		return found
	}
}

val vanillaSoundAdvice = "A vanilla sound on this list breaks the tone (the explosion on a breach, music in the unbuilt depth). Use a mod sound " +
	"(DeepSound) or add one. A line that must keep the vanilla sound ends with '// vanilla-ok: <reason>', or has it on the contiguous // " +
	"lines above its statement. The list is gradle/banned-vanilla-sounds.txt: add a line to ban another."

/** The problems over the fixture tree {@code root}: its .java files and the one banned-vanilla-sounds.txt. */
val vanillaSoundProblems: (File, File, (String) -> String, (List<String>) -> List<String>, (List<String>, Int, (String) -> Boolean) -> Boolean) -> List<String> =
	{ root, base, blankLiterals, codeLines, markedNear ->
		val files = root.walkTopDown().filter { it.isFile }.toList()
		val list = files.singleOrNull { it.name == "banned-vanilla-sounds.txt" }
			?: throw GradleException("the fixture tree needs exactly one banned-vanilla-sounds.txt")
		VanillaSoundGate.problems(files.filter { it.name.endsWith(".java") }, list, base, blankLiterals, codeLines, markedNear)
	}
// Shared with checkGametestFixtures (gametest.gradle.kts), as keybind-gate.gradle.kts does.
extra["vanillaSoundProblems"] = vanillaSoundProblems
extra["vanillaSoundAdvice"] = vanillaSoundAdvice

tasks.register("checkVanillaSoundGate") {
	group = "verification"
	description = "Fails on a banned vanilla sound or music in src/main or src/client with no '// vanilla-ok: <reason>' marker."
	@Suppress("UNCHECKED_CAST")
	val blankLiterals = project.extra["blankLiterals"] as (String) -> String
	@Suppress("UNCHECKED_CAST")
	val codeLines = project.extra["codeLines"] as (List<String>) -> List<String>
	@Suppress("UNCHECKED_CAST")
	val markedNear = project.extra["markedNear"] as (List<String>, Int, (String) -> Boolean) -> Boolean
	val root = projectDir
	val list = file("gradle/banned-vanilla-sounds.txt")
	val sources = fileTree(root) { include("src/main/java/**/*.java", "src/client/java/**/*.java") }
	val advice = vanillaSoundAdvice
	inputs.files(sources).withPropertyName("sources")
	inputs.file(list).withPropertyName("list")
	doLast {
		val problems = VanillaSoundGate.problems(sources.files.toList(), list, root, blankLiterals, codeLines, markedNear)
		if (problems.isNotEmpty()) {
			throw GradleException("Banned vanilla sounds. $advice\n  " + problems.joinToString("\n  "))
		}
	}
}

tasks.named("check") { dependsOn("checkVanillaSoundGate") }
