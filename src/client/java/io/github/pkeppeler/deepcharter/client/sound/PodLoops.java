package io.github.pkeppeler.deepcharter.client.sound;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.SoundTuning;

/**
 * The engine, drill and rotor loops of every pod with a pilot, heard from where the pod is. Each loop runs while the pod is in
 * its state and ends the tick it leaves it: a pod that starts drilling swaps its engine loop for a drill loop. A pod nobody sits in,
 * or one without power ({@link PodEntity#stranded}), is silent.
 */
public final class PodLoops {
	/** One loop slot of a pod: at most one loop of each plays at a time. */
	private enum Channel {
		/** Idle or driving; the drill replaces it while drilling. */
		ENGINE(PodLoops::engine),
		ROTOR(pod -> pod.flying() ? Optional.of(DeepSound.POD_ROTOR) : Optional.empty());

		private final Function<PodEntity, Optional<DeepSound>> sound;

		Channel(Function<PodEntity, Optional<DeepSound>> sound) {
			this.sound = sound;
		}

		/** The sound this channel should make for {@code pod} now: none when the pod has no pilot or no power. */
		Optional<DeepSound> soundOf(PodEntity pod) {
			return pod.getControllingPassenger() == null || pod.stranded() ? Optional.empty() : sound.apply(pod);
		}
	}

	private record Key(int podId, Channel channel) {
	}

	private static final Map<Key, Loop> LOOPS = new HashMap<>();
	private static final Set<Integer> MOVING = new HashSet<>();
	private static Map<Integer, Vec3> lastPositions = new HashMap<>();

	private PodLoops() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(PodLoops::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			LOOPS.clear();
			MOVING.clear();
			lastPositions = new HashMap<>();
		});
	}

	private static Optional<DeepSound> engine(PodEntity pod) {
		if (pod.drilling()) {
			return Optional.of(pod.drillDirection() == Direction.DOWN ? DeepSound.POD_ENGINE_DRILL_DOWN : DeepSound.POD_ENGINE_DRILL_SIDE);
		}
		return Optional.of(MOVING.contains(pod.getId()) ? DeepSound.POD_ENGINE_DRIVE : DeepSound.POD_ENGINE_IDLE);
	}

	/** Records which pods moved since the last client tick. A pod's own old position is not that: a remote pod is lerped. */
	private static void trackMovement(Minecraft client) {
		double speed = SoundTuning.DEFAULT.movingSpeed();
		Map<Integer, Vec3> positions = new HashMap<>();
		MOVING.clear();
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity instanceof PodEntity pod) {
				Vec3 last = lastPositions.get(pod.getId());
				if (last != null && last.distanceToSqr(pod.position()) > speed * speed) {
					MOVING.add(pod.getId());
				}
				positions.put(pod.getId(), pod.position());
			}
		}
		lastPositions = positions;
	}

	private static void tick(Minecraft client) {
		LOOPS.values().removeIf(Loop::isStopped);
		if (client.level == null) {
			return;
		}
		trackMovement(client);
		for (Entity entity : client.level.entitiesForRendering()) {
			// A pod the client has not ticked yet may be one it drops again at once: the engine would tick its loop for ever.
			if (entity instanceof PodEntity pod && !pod.isRemoved() && pod.tickCount > 0) {
				for (Channel channel : Channel.values()) {
					Key key = new Key(pod.getId(), channel);
					Optional<DeepSound> wanted = channel.soundOf(pod);
					Loop playing = LOOPS.get(key);
					if (playing != null && wanted.filter(playing.sound::equals).isPresent()) {
						continue;
					}
					if (playing != null) {
						playing.end();
						LOOPS.remove(key);
					}
					if (wanted.isPresent()) {
						Loop loop = new Loop(pod, channel, wanted.get());
						LOOPS.put(key, loop);
						client.getSoundManager().play(loop);
					}
				}
			}
		}
	}

	/** Follows its pod and ends itself when the pod is gone or no longer in the state the loop is for. */
	private static final class Loop extends AbstractTickableSoundInstance {
		private final PodEntity pod;
		private final Channel channel;
		private final DeepSound sound;

		private Loop(PodEntity pod, Channel channel, DeepSound sound) {
			super(sound.event(), SoundSource.NEUTRAL, RandomSource.create());
			this.pod = pod;
			this.channel = channel;
			this.sound = sound;
			this.looping = true;
			follow();
		}

		@Override
		public void tick() {
			if (pod.isRemoved() || !channel.soundOf(pod).filter(sound::equals).isPresent()) {
				end();
				return;
			}
			follow();
		}

		private void follow() {
			x = pod.getX();
			y = pod.getY();
			z = pod.getZ();
		}

		private void end() {
			stop();
		}
	}
}
