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
