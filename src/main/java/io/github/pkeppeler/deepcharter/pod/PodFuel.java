package io.github.pkeppeler.deepcharter.pod;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Fuel drain, refuelling and stranding. {@link PodData#FUEL} is a percentage of the tank:
 * litres / {@code tankLitres} * 100, so 100 is full whatever the tank size.
 */
public final class PodFuel {
	private static final float TICKS_PER_SECOND = 20f;

	/** What the pod is doing, which sets how fast it burns fuel. */
	public enum Activity { IDLE, MOVING, DRILLING }

	private PodFuel() {
	}

	/** Refuel by using coal or charcoal on the pod. An event, so that PodEntity.interact stays the mount's. */
	public static void init() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				entity instanceof PodEntity pod ? refuel(player, hand, pod) : InteractionResult.PASS);
	}

	/** Fuel burned per tick, in percent of the tank. */
	public static float drainPercentPerTick(Activity activity) {
		PodTuning.Fuel tuning = PodTuning.DEFAULT.fuel();
		float litresPerSecond = switch (activity) {
			case IDLE -> tuning.idleLitresPerSecond();
			case MOVING -> tuning.movingLitresPerSecond();
			case DRILLING -> tuning.drillingLitresPerSecond();
		};
		return litresToPercent(litresPerSecond / TICKS_PER_SECOND);
	}

	/** True at the beep threshold and below. */
	public static boolean isLow(float fuelPercent) {
		return fuelPercent <= PodTuning.DEFAULT.fuel().lowFuelPercent();
	}

	/** Called every pod tick, on both sides; only the server burns fuel, and a stranded pod is powered off. */
	public static void tick(PodEntity pod) {
		if (pod.level().isClientSide() || pod.stranded()) {
			return;
		}
		float fuel = Math.max(0f, pod.fuel() - drainPercentPerTick(activity(pod)));
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
		if ((!stack.is(Items.COAL) && !stack.is(Items.CHARCOAL)) || pod.fuel() >= full) {
			return InteractionResult.PASS;
		}
		if (pod.level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		float added = litresToPercent(PodTuning.DEFAULT.fuel().refuelLitres());
		pod.setFuel(Math.min(full, pod.fuel() + added));
		pod.setStranded(false);
		stack.consume(1, player);
		return InteractionResult.SUCCESS;
	}

	private static float litresToPercent(float litres) {
		return litres / PodTuning.DEFAULT.fuel().tankLitres() * PodTuning.DEFAULT.shell().fullFuel();
	}
}
