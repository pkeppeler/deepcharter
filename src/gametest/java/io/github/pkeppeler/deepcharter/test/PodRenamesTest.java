package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

/**
 * Server GameTests for #416: a pod saved under the id of a renamed pod entity type loads as the new type with its chassis and cargo.
 * The saved data is a pod's real entity NBT with the old id, read the way a chunk's entities are read (the {@code Entities} list of an
 * entity chunk, through {@code EntityType.loadEntitiesRecursive}, as {@code EntityStorage} does).
 */
public class PodRenamesTest {
	/** The one rename in the table, which exists for this test. */
	private static final String RETIRED = "deepcharter:retired_pod_for_test";

	private static CompoundTag savedPodUnder(ServerLevel level, String id) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setHull(11.5f);
		pod.setFuel(3.25f);
		pod.setStranded(true);
		if (!pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM))) {
			throw new IllegalStateException("the fixture pod must take one ore");
		}
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		CompoundTag saved = output.buildResult();
		saved.putString("id", id);
		return saved;
	}

	private static List<Entity> loadAsAChunkDoes(ServerLevel level, CompoundTag... saved) {
		ListTag entities = new ListTag();
		for (CompoundTag tag : saved) {
			entities.add(tag);
		}
		CompoundTag chunk = new CompoundTag();
		chunk.put("Entities", entities);
		ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), chunk);
		return EntityType.loadEntitiesRecursive(input.childrenListOrEmpty("Entities"), level, EntitySpawnReason.LOAD).toList();
	}

	@GameTest
	public void aPodSavedUnderARenamedTypeLoadsAsTheNewTypeWithItsChassisAndCargo(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Identifier retired = Identifier.parse(RETIRED);
		if (BuiltInRegistries.ENTITY_TYPE.containsKey(retired)) {
			throw helper.assertionException(Component.literal("the old id must not be registered, or vanilla would load it without the rename"));
		}
		List<Entity> loaded = loadAsAChunkDoes(level, savedPodUnder(level, RETIRED));
		if (loaded.size() != 1 || !(loaded.getFirst() instanceof PodEntity pod) || pod.getType() != PodRegistry.POD) {
			throw helper.assertionException(Component.literal("the pod must load as the new type, got " + loaded));
		}
		if (pod.chassis() != PodRegistry.chassisOf(PodRegistry.POD) || pod.hull() != 11.5f || pod.fuel() != 3.25f || !pod.stranded()
				|| pod.cargo().entries().size() != 1 || pod.cargo().entries().getFirst().stack().getItem() != OreRegistry.stack(OreType.IRONIUM).getItem()
				|| pod.cargoUsed() != 1) {
			throw helper.assertionException(Component.literal(String.format(
					"the renamed pod must keep its chassis, hull, fuel, stranded flag and cargo, got %s hull %s fuel %s stranded %s cargo %s",
					pod.chassis().id(), pod.hull(), pod.fuel(), pod.stranded(), pod.cargo().entries())));
		}
		helper.succeed();
	}

	@GameTest
	public void aSavedEntityUnderAnIdNobodyRenamedIsStillDropped(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		List<Entity> loaded = loadAsAChunkDoes(level, savedPodUnder(level, "deepcharter:never_a_pod_type"));
		if (!loaded.isEmpty()) {
			throw helper.assertionException(Component.literal("an unknown id outside the table must not load, got " + loaded));
		}
		helper.succeed();
	}
}
