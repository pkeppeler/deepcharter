package io.github.pkeppeler.deepcharter.client.theme;

/** ARGB arithmetic on colours the theme gave. The only place in client code that writes a mask. */
public final class Colors {
	private static final int ALPHA_MASK = 0xFF000000;
	private static final int RGB_MASK = 0x00FFFFFF;

	private Colors() {
	}

	/** {@code argb} with its alpha forced to opaque. */
	public static int opaque(int argb) {
		return argb | ALPHA_MASK;
	}

	/** The colour without its alpha: as vanilla text styles take it, and the transparent far end of a gradient. */
	public static int rgb(int argb) {
		return argb & RGB_MASK;
	}

	/** The colour of {@code argb} at {@code alpha} (0 to 255). */
	public static int withAlpha(int argb, int alpha) {
		return rgb(argb) | alpha << 24;
	}
}
