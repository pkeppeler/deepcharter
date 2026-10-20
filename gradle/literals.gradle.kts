// Shared by the source gates (gametest.gradle.kts, colour-gate.gradle.kts): blankLiterals(text) replaces every string literal with "", every
// text block with "" (its lines become empty) and every char literal with '', and keeps comments as they are, so a gate that looks for a
// `// <name>: <reason>` marker never counts one that sits inside a literal. Newlines are kept, so line numbers still line up.
// Applied scripts cannot share declarations, so the function goes in the project's extra properties, typed (String) -> String.
val blankLiterals: (String) -> String = { text ->
	val out = StringBuilder()
	var i = 0
	val n = text.length
	while (i < n) {
		val c = text[i]
		if (text.startsWith("\"\"\"", i)) {
			i += 3
			out.append("\"\"")
			while (i < n && !text.startsWith("\"\"\"", i)) {
				if (text[i] == '\\' && i + 1 < n) {
					if (text[i + 1] == '\n') out.append('\n')
					i += 2
				} else {
					if (text[i] == '\n') out.append('\n')
					i++
				}
			}
			i += 3
		} else if (c == '"' || c == '\'') {
			i++
			out.append(c).append(c)
			while (i < n && text[i] != c && text[i] != '\n') {
				i += if (text[i] == '\\' && i + 1 < n && text[i + 1] != '\n') 2 else 1
			}
			if (i < n && text[i] == c) i++
		} else if (text.startsWith("//", i)) {
			var end = text.indexOf('\n', i)
			if (end < 0) end = n
			out.append(text, i, end)
			i = end
		} else if (text.startsWith("/*", i)) {
			var end = text.indexOf("*/", i + 2)
			end = if (end < 0) n else end + 2
			out.append(text, i, end)
			i = end
		} else {
			out.append(c)
			i++
		}
	}
	out.toString()
}
extra["blankLiterals"] = blankLiterals

// A formatter (palantir-java-format) wraps a long statement over several lines and moves a trailing `// <marker>:` comment to the
// end of the wrapped statement, so "the flagged line or the one above" no longer finds it. markedNear(lines, line, isMarker) is
// true when isMarker holds for the flagged line (0-based `line`), the line above it, any line of the statement the flagged line is
// in, or a comment line directly above that statement. A statement runs from after the previous line that ends in `;`, `{` or `}`
// to the next one, judged on the code of each line: `//` and block comments (a URL in javadoc, a `;` in prose) are cut out first.
// `lines` come from blankLiterals, so a `//` or `/*` inside a literal is already gone and cannot pass for a comment. The walk is
// capped at 12 lines each way, so a marker never reaches far.
val markedNear: (List<String>, Int, (String) -> Boolean) -> Boolean = { lines, line, isMarker ->
	var inBlock = false
	val codes = lines.map { l ->
		val code = StringBuilder()
		var i = 0
		while (i < l.length) {
			if (inBlock) {
				val end = l.indexOf("*/", i)
				if (end < 0) i = l.length else { inBlock = false; i = end + 2 }
			} else if (l.startsWith("//", i)) {
				i = l.length
			} else if (l.startsWith("/*", i)) {
				inBlock = true
				i += 2
			} else {
				code.append(l[i])
				i++
			}
		}
		code.toString().trim()
	}
	fun endsStatement(i: Int): Boolean = codes[i].let { it.endsWith(";") || it.endsWith("{") || it.endsWith("}") }
	var top = line
	while (top > 0 && line - top < 12) {
		val above = top - 1
		if (lines[above].isBlank() || endsStatement(above)) break
		top--
	}
	var bottom = line
	while (bottom + 1 < lines.size && bottom - line < 12 && !endsStatement(bottom)) bottom++
	(top..bottom).any { isMarker(lines[it]) } || (line > 0 && isMarker(lines[line - 1]))
}
extra["markedNear"] = markedNear
