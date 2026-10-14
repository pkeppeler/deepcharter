package io.github.pkeppeler.deepcharter.test.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonParser;

/** The length of a signal-decay time cell (#502) in ticks, read from the shipped cockpit theme, so a server test judges a fault in the same cell the client draws it in. */
public final class DecayCells {
	private DecayCells() {
	}

	public static int ticks() {
		try (InputStream stream = DecayCells.class.getResourceAsStream("/assets/deepcharter/theme/cockpit.json"); Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader).getAsJsonObject().get("decayCellTicks").getAsInt();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
