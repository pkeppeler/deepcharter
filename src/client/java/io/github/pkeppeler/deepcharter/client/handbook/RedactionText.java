package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a string that uses {@code ||} redaction marks into words. The words between a pair of marks are redacted. An odd number
 * of marks redacts the rest of the string, so an unclosed mark never leaks text. Used by the contents entries and the Appendix A
 * lines, and by nothing else.
 */
public final class RedactionText {
	private RedactionText() {
	}

	/** One word, whether it is redacted, and whether a space came before it. */
	public record Token(String text, boolean redacted, boolean spaceBefore) {
	}

	public static List<Token> parse(String text) {
		List<Token> tokens = new ArrayList<>();
		String[] segments = text.split("\\|\\|", -1);
		boolean space = false;
		for (int index = 0; index < segments.length; index++) {
			boolean redacted = index % 2 == 1;
			StringBuilder word = new StringBuilder();
			for (char letter : segments[index].toCharArray()) {
				if (letter == ' ') {
					if (!word.isEmpty()) {
						tokens.add(new Token(word.toString(), redacted, space));
						word.setLength(0);
					}
					space = true;
				} else {
					if (word.isEmpty() && tokens.isEmpty()) {
						space = false;
					}
					word.append(letter);
				}
			}
			if (!word.isEmpty()) {
				tokens.add(new Token(word.toString(), redacted, space));
				space = false;
			}
		}
		return tokens;
	}
}
