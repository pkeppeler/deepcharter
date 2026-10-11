package io.github.pkeppeler.deepcharter.test;

import java.util.function.BiConsumer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.server.level.ParticleStatus;

import io.github.pkeppeler.deepcharter.test.support.ClientChecks;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * {@link ClientChecks#withHudHidden} puts the HUD (and the particle option) back when the body throws, from either starting state. The client
 * is shared by every client test, so a leak here would fail the next test that draws on the HUD.
 */
public class HudHiddenClientTest implements FabricClientGameTest {
	private static final class Boom extends RuntimeException {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		boolean hudWasHidden = hidden(context);
		try {
			for (boolean startsHidden : new boolean[] {false, true}) {
				setHidden(context, startsHidden);
				requireRestoredAfterThrow(context, startsHidden);
				requireRestoredAfterReturn(context, startsHidden);
				requireNestingRestoresEachLevel(context, startsHidden);
			}
			requireParticlesRestoredAfterThrow(context);
		} finally {
			setHidden(context, hudWasHidden);
		}
	}

	private static void requireRestoredAfterThrow(ClientGameTestContext context, boolean startsHidden) {
		requireRestoredAfterThrow(context, startsHidden, ClientChecks::withHudHidden, true);
		requireRestoredAfterThrow(context, startsHidden, ClientChecks::withHudShown, false);
	}

	private static void requireRestoredAfterThrow(ClientGameTestContext context, boolean startsHidden, BiConsumer<ClientGameTestContext, Runnable> scope, boolean hiddenInside) {
		try {
			scope.accept(context, () -> {
				require(hidden(context) == hiddenInside, "The HUD should be hidden=" + hiddenInside + " inside the body");
				throw new Boom();
			});
			throw new AssertionError("The body's exception should leave the HUD scope");
		} catch (Boom expected) {
			requireHidden(context, startsHidden, "After a throw");
		}
	}

	private static void requireNestingRestoresEachLevel(ClientGameTestContext context, boolean startsHidden) {
		ClientChecks.withHudHidden(context, () -> {
			ClientChecks.withHudShown(context, () -> require(!hidden(context), "The HUD should be shown in the inner body"));
			require(hidden(context), "The HUD should be hidden again after the inner body");
			try {
				ClientChecks.withHudShown(context, () -> {
					throw new Boom();
				});
			} catch (Boom expected) {
				require(hidden(context), "The HUD should be hidden again after the inner body threw");
			}
		});
		requireHidden(context, startsHidden, "After the nested bodies");
	}

	private static void requireRestoredAfterReturn(ClientGameTestContext context, boolean startsHidden) {
		ClientChecks.withHudHidden(context, () -> require(hidden(context), "The HUD should be hidden inside the body"));
		requireHidden(context, startsHidden, "After the body");
		ClientChecks.withHudShown(context, () -> require(!hidden(context), "The HUD should be shown inside the body"));
		requireHidden(context, startsHidden, "After a shown body");
	}

	private static void requireParticlesRestoredAfterThrow(ClientGameTestContext context) {
		ParticleStatus before = particles(context);
		boolean hudBefore = hidden(context);
		ParticleStatus other = before == ParticleStatus.MINIMAL ? ParticleStatus.ALL : ParticleStatus.MINIMAL;
		try {
			ClientChecks.withHudHidden(context, other, () -> {
				require(particles(context) == other, "The particle option should be " + other + " inside the body");
				throw new Boom();
			});
			throw new AssertionError("The body's exception should leave withHudHidden");
		} catch (Boom expected) {
			require(particles(context) == before, "After a throw the particle option should be back to " + before + ", was " + particles(context));
			requireHidden(context, hudBefore, "After a throw");
		}
	}

	private static void requireHidden(ClientGameTestContext context, boolean expected, String when) {
		require(hidden(context) == expected, when + " the HUD should be back to hidden=" + expected + ", was hidden=" + hidden(context));
	}

	private static boolean hidden(ClientGameTestContext context) {
		return context.computeOnClient(client -> client.gui.hud.isHidden());
	}

	private static ParticleStatus particles(ClientGameTestContext context) {
		return context.computeOnClient(client -> client.options.particles().get());
	}

	/** Puts the HUD where the case starts. */
	private static void setHidden(ClientGameTestContext context, boolean hidden) {
		context.runOnClient(client -> {
			if (client.gui.hud.isHidden() != hidden) {
				client.gui.hud.toggle(); // hud-toggle: sets the starting state of the case under test; the test's finally puts the client's own state back
			}
		});
	}
}
