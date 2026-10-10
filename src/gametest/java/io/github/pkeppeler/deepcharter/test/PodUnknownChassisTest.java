package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for #405: the entity type decides a pod's chassis, so a saved chassis id that is unknown or missing is logged and the
 * type's chassis is used, as for a known but different id. The load never crashes and the rest of the pod's data is read as usual.
 */
public class PodUnknownChassisTest {
	private static final String GONE = "deepcharter_gone:vanished";

	/** The saved data of a pod with a distinctive hull, fuel, stranded flag and one ore in the bay. */
	private static CompoundTag savedPod(ServerLevel level) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setHull(11.5f);
		pod.setFuel(3.25f);
		pod.setStranded(true);
		if (!pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM))) {
			throw new IllegalStateException("the fixture pod must take one ore");
		}
		return save(level, pod);
	}

	private static CompoundTag save(ServerLevel level, PodEntity pod) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		return output.buildResult();
	}

	private static PodEntity load(ServerLevel level, CompoundTag saved) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
		return pod;
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	/** Fails unless {@code loaded} is the pod {@link #savedPod} saved, on the chassis of its type. */
	private static void requireSamePod(GameTestHelper helper, PodEntity loaded) {
		if (loaded.chassis() != PodRegistry.chassisOf(PodRegistry.POD) || loaded.hull() != 11.5f || loaded.fuel() != 3.25f || !loaded.stranded()
				|| loaded.cargo().entries().size() != 1 || loaded.cargo().entries().getFirst().stack().getItem() != OreRegistry.stack(OreType.IRONIUM).getItem()
				|| loaded.cargoUsed() != 1) {
			throw failure(helper, "the pod must load with the type's chassis and its saved hull, fuel, stranded flag and cargo, got %s hull %s fuel %s stranded %s cargo %s",
					loaded.chassis().id(), loaded.hull(), loaded.fuel(), loaded.stranded(), loaded.cargo().entries());
		}
	}

	@GameTest
	public void aPodSavedWithAnUnknownChassisIdLoadsWithTheTypesChassisAndItsData(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CompoundTag saved = savedPod(level);
		saved.putString("chassis", GONE);
		PodEntity loaded = load(level, saved);
		requireSamePod(helper, loaded);
		// It saves with the type's chassis, and that loads again to the same pod.
		CompoundTag again = save(level, loaded);
		if (!PodRegistry.chassisOf(PodRegistry.POD).id().equals(again.getStringOr("chassis", ""))) {
			throw failure(helper, "the pod must save the chassis of its type, got %s", again.get("chassis"));
		}
		requireSamePod(helper, load(level, again));
		helper.succeed();
	}

	@GameTest
	public void aPodSavedWithNoChassisIdLoadsWithTheTypesChassisAndItsData(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CompoundTag saved = savedPod(level);
		saved.remove("chassis");
		requireSamePod(helper, load(level, saved));
		saved.putInt("chassis", 7);
		requireSamePod(helper, load(level, saved));
		helper.succeed();
	}

	@GameTest(maxTicks = 40)
	public void aPodLoadedWithAnUnknownChassisIdCanBeRiddenAndRefuelled(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CompoundTag saved = savedPod(level);
		saved.putString("chassis", GONE);
		PodEntity pod = load(level, saved);
		pod.setPos(helper.absoluteVec(new Vec3(2.5, 2, 2.5)));
		level.addFreshEntity(pod);
		MockPlayer player = MockPlayers.join(helper, "rider");
		try {
			player.player().setGameMode(GameType.SURVIVAL);
			player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 2));
			float before = pod.fuel();
			UseEntityCallback.EVENT.invoker().interact(player.player(), level, InteractionHand.MAIN_HAND, pod, null);
			if (pod.fuel() <= before || pod.stranded() || player.player().getItemInHand(InteractionHand.MAIN_HAND).getCount() != 1) {
				throw failure(helper, "coal must refuel the pod and use one item, got fuel %s from %s, stranded %s", pod.fuel(), before, pod.stranded());
			}
			if (!player.player().startRiding(pod) || pod.getControllingPassenger() != player.player()) {
				throw failure(helper, "the player must be able to board and pilot the pod");
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}
}
