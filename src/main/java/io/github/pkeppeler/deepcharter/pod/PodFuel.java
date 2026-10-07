package io.github.pkeppeler.deepcharter.pod;

import java.util.OptionalDouble;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Fuel drain, refuelling and stranding; FUEL is a percentage, so 100 is full whatever the tank size. */
public final class PodFuel {
	private static final float TICKS_PER_SECOND = 20f;

	/** What the pod is doing, which sets how fast it burns fuel. */
	public enum Activity { IDLE, MOVING, DRILLING }

	private PodFuel() {
	}

	/** Refuel by using a fuel item ({@link PodFuelItems}) on the pod. An event, so that PodEntity.interact stays the mount's. */
	public static void init() {
		PodFuelItems.init();
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				entity instanceof PodEntity pod ? refuel(player, hand, pod) : InteractionResult.PASS);
	}

	/** Fuel burned per tick, in percent of the tank, by a pod with these stats. */
	public static float drainPercentPerTick(PodStats stats, Activity activity) {
		float litresPerSecond = switch (activity) {
			case IDLE -> stats.idleLitresPerSecond();
			case MOVING -> stats.movingLitresPerSecond();
			case DRILLING -> stats.drillingLitresPerSecond();
		};
		return litresToPercent(stats, litresPerSecond / TICKS_PER_SECOND);
	}

	/** True at the beep threshold and below. */
	public static boolean isLow(float fuelPercent) {
		return fuelPercent <= PodTuning.DEFAULT.fuel().lowFuelPercent();
	}

	/** Called every pod tick, on the server only; a pod without power burns no fuel. */
	static void tick(PodEntity pod, PodStats stats) {
		if (!PodEvents.isPowered(pod)) {
			return;
		}
		float fuel = Math.max(0f, pod.fuel() - drainPercentPerTick(stats, activity(pod)));
		pod.setFuel(fuel);
		if (fuel <= 0f) {
			pod.setStranded(true);
		}
	}

	private static Activity activity(PodEntity pod) {
		if (pod.drilling()) {
			return Activity.DRILLING;
		}
		boolean driving = pod.getDeltaMovement().horizontalDistanceSqr() > 0 || pod.flying();
		return driving ? Activity.MOVING : Activity.IDLE;
	}

	private static InteractionResult refuel(Player player, InteractionHand hand, PodEntity pod) {
		ItemStack stack = player.getItemInHand(hand);
		float full = PodTuning.DEFAULT.shell().fullFuel();
		// The client decides from the synced tag: the litres table is the server's alone.
		if (!stack.is(PodFuelItems.TAG) || pod.fuel() >= full) {
			return InteractionResult.PASS;
		}
		if (pod.level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		OptionalDouble litres = PodFuelItems.litresOf(stack);
		if (litres.isEmpty()) {
			// Tagged but with no litres entry: logged when the data loaded, and not fuel.
			return InteractionResult.PASS;
		}
		float added = litresToPercent(PodStats.of(pod), (float) litres.getAsDouble());
		pod.setFuel(Math.min(full, pod.fuel() + added));
		pod.setStranded(false);
		stack.consume(1, player);
		return InteractionResult.SUCCESS;
	}

	private static float litresToPercent(PodStats stats, float litres) {
		return litres / stats.tankLitres() * PodTuning.DEFAULT.shell().fullFuel();
	}
}
