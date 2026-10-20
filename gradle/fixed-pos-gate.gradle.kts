// Fixed-position gate (#533, evidence.md): an evidence scenario that stages at a literal world Y breaks when the world moves, and a Y near a
// layer floor records the signal-decay blackout (#543). This fails the build on one in src/gametest/java/**/test/evidence unless the statement it is
// in, or the comment lines above that statement, carry `// fixed-pos: <reason>`: a reason that says why this place cannot come from the colony's
// anchors, a carved RoomCarver room, the layer's geometry or the decay tuning (EvidenceScenario.LIVE_GAUGES_FLOOR_Y).
//
// What it flags, on code with comments and string literals blanked:
//   - a constant named Y or *_Y set to a numeric literal (FLOOR_Y = 4); one derived from it (FLOOR_Y - 4) is not;
//   - a literal Y, of any size, in new BlockPos(x, y, z), BlockPos.containing, new Vec3 or new Vec3i when X and Z are literals of 100 or more in
//     magnitude too: an absolute place (new BlockPos(3616, 40, 3616));
//   - a literal Y in teleportTo, setPos, setPosRaw, moveTo, snapTo, absMoveTo or atY.
// What it leaves alone, on purpose: a vector with a small or non-literal X or Z (new Vec3(0, 1.62, -4), new BlockPos(-6, 0, -7), new BlockPos(X, 100, Z)).
// The scenarios hold about a hundred of the small ones and every one is an offset from a place the scenario derived, which no scan of the text can
// tell from a position. A literal X or Z alone is not flagged either: a scenario stages in its own carved room, far out in a layer's dimension,
// where no world change reaches it. Known blind spots, so a reviewer must still look: a Y held in a lowercase name or a local (`int floor = 4`), a Y
// assigned after its declaration, a Y reached by `.above(n)` or `.offset(...)` from a position that is only known at run time, a Y with a variable X
// or Z (`new BlockPos(X, 100, Z)`), and a call split from its own parenthesis by a line break.
//
// tools/tests/fixed_pos_gate_check.py runs the scan over fixture trees (checkGametestFixtures in gametest.gradle.kts), so a change that makes
// the pattern miss fails there, not silently.
object FixedPosGate {
	private val NUMBER = Regex("""[-+]?\d[\d_]*(?:\.\d+)?[dDfFlL]?""")
	private val MARKER = Regex("""//\s*fixed-pos:\s*\S""")
	private val CONSTANT = Regex("""\b(?:int|long|float|double)\s+(?:[A-Z0-9]+_)*Y\s*=\s*${NUMBER.pattern}\s*;""")
	private val POSITION = Regex("""\b(?:new\s+(?:BlockPos|Vec3i|Vec3)|BlockPos\s*\.\s*containing)\s*\(""")
	private val MOVE = Regex("""\.\s*(teleportTo|setPosRaw|setPos|moveTo|snapTo|absMoveTo|atY)\s*\(""")
	private val COMMENT = Regex("(?s)/\\*.*?\\*/|//[^\\n]*")
	private val notNewline = Regex("[^\\n]")
	private const val MIN_LITERAL_XZ = 100.0

	private fun isNumber(argument: String?): Boolean = argument != null && NUMBER.matches(argument)

	private fun magnitude(argument: String): Double = Math.abs(argument.replace("_", "").trimEnd('d', 'D', 'f', 'F', 'l', 'L').toDouble())

	/** The top-level arguments of the call whose opening parenthesis ends just before {@code from}. */
	private fun arguments(code: String, from: Int): List<String> {
		val found = mutableListOf<String>()
		val current = StringBuilder()
		var depth = 1
		var i = from
		while (i < code.length && depth > 0) {
			val c = code[i]
			if (c == '(' || c == '[' || c == '{') depth++
			if (c == ')' || c == ']' || c == '}') depth--
			if (depth == 0) break
			if (c == ',' && depth == 1) {
				found.add(current.toString().trim())
				current.setLength(0)
			} else {
				current.append(c)
			}
			i++
		}
		found.add(current.toString().trim())
		return found
	}

	/** Lines (1-based) of {@code text}, already passed through blankLiterals, with a literal world Y and no marker on their statement or the comment lines above it. */
	fun violations(text: String, markedNear: (List<String>, Int, (String) -> Boolean) -> Boolean): List<Int> {
		val code = COMMENT.replace(text) { it.value.replace(notNewline, " ") }
		val lines = text.reader().readLines()
		val starts = mutableListOf<Int>()
		CONSTANT.findAll(code).forEach { starts.add(it.range.first) }
		POSITION.findAll(code).forEach { match ->
			val args = arguments(code, match.range.last + 1)
			if (args.size == 3 && args.all { isNumber(it) } && magnitude(args[0]) >= MIN_LITERAL_XZ && magnitude(args[2]) >= MIN_LITERAL_XZ) starts.add(match.range.first)
		}
		MOVE.findAll(code).forEach { match ->
			val args = arguments(code, match.range.last + 1)
			val y = when (match.groupValues[1]) {
				"atY" -> args.firstOrNull()
				// teleportTo(level, x, y, z, relatives, yaw, pitch, ...) against teleportTo(x, y, z) and teleportTo(level, pos, yaw, pitch).
				"teleportTo" -> if (args.size >= 5) args[2] else if (args.size == 3) args[1] else null
				else -> if (args.size >= 3) args[1] else null
			}
			if (isNumber(y)) starts.add(match.range.first)
		}
		return starts.map { code.substring(0, it).count { c -> c == '\n' } }.distinct().sorted()
			.filter { !markedNear(lines, it) { line -> MARKER.containsMatchIn(line) } }
			.map { it + 1 }
	}
}

val fixedPosAdvice = "Derive the place: the colony's anchors (Colony.anchor, ColonySite), a RoomCarver room, the layer's own geometry, or " +
	"EvidenceScenario.LIVE_GAUGES_FLOOR_Y and the decay tuning. A place that cannot be derived ends with '// fixed-pos: <reason>'. Not flagged, so check by eye: " +
	"a lowercase or local Y, a Y set after its declaration, .above(n) from a run-time position, a Y beside a variable X or Z, and small all-literal offsets. See evidence.md."

/** The problems (file:line message) in the evidence scenarios under {@code root}, relative to {@code base}. */
val fixedPosProblems: (File, File, (String) -> String, (List<String>, Int, (String) -> Boolean) -> Boolean) -> List<String> = { root, base, blankLiterals, markedNear ->
	val problems = mutableListOf<String>()
	root.walkTopDown().filter { it.isFile && it.name.endsWith(".java") && it.invariantSeparatorsPath.contains("/test/evidence/") }.toList().sorted().forEach { source ->
		val lines = source.reader().readLines()
		FixedPosGate.violations(blankLiterals(source.readText()), markedNear).forEach { n ->
			problems.add("${source.relativeTo(base)}:$n stages at a literal world Y, which breaks when the world moves or lands in the signal-decay blackout: ${lines[n - 1].trim()}")
		}
	}
	problems
}
// Shared with checkGametestFixtures (gametest.gradle.kts), which runs the scan over fixture trees.
extra["fixedPosProblems"] = fixedPosProblems
extra["fixedPosAdvice"] = fixedPosAdvice

tasks.register("checkFixedPosGate") {
	group = "verification"
	description = "Fails on a literal world Y in an evidence scenario that carries no '// fixed-pos: <reason>' marker."
	@Suppress("UNCHECKED_CAST")
	val blankLiterals = project.extra["blankLiterals"] as (String) -> String
	@Suppress("UNCHECKED_CAST")
	val markedNear = project.extra["markedNear"] as (List<String>, Int, (String) -> Boolean) -> Boolean
	val root = projectDir
	val gametestRoot = file("src/gametest/java")
	val scenarios = fileTree(gametestRoot) { include("**/test/evidence/**/*.java") }
	val problemsIn = fixedPosProblems
	val advice = fixedPosAdvice
	inputs.files(scenarios).withPropertyName("scenarios")
	doLast {
		val problems = problemsIn(gametestRoot, root, blankLiterals, markedNear)
		if (problems.isNotEmpty()) {
			throw GradleException("Literal world Y in evidence scenarios. $advice\n  " + problems.joinToString("\n  "))
		}
	}
}

tasks.named("check") { dependsOn("checkFixedPosGate") }
