package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodRenames;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

public class PodRenamesTest {
    private static final String RETIRED = "deepcharter:retired_pod_for_test";

    private static CompoundTag savedPodUnder(ServerLevel level, String id) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
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
        return EntityType.loadEntitiesRecursive(input.childrenListOrEmpty("Entities"), level, EntitySpawnReason.LOAD)
                .toList();
    }

    @GameTest
    public void aPodSavedUnderARenamedTypeLoadsAsTheNewTypeWithItsChassisAndCargo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Identifier retired = Identifier.parse(RETIRED);
        if (BuiltInRegistries.ENTITY_TYPE.containsKey(retired)) {
            throw failure(helper, "the old id must not be registered, or vanilla would load it without the rename");
        }
        List<Entity> loaded = loadAsAChunkDoes(level, savedPodUnder(level, RETIRED));
        if (loaded.size() != 1 || !(loaded.getFirst() instanceof PodEntity pod) || pod.getType() != PodRegistry.POD) {
            throw failure(helper, "the pod must load as the new type, got %s", loaded);
        }
        if (pod.chassis() != PodRegistry.chassisOf(PodRegistry.POD)
                || pod.hull() != 11.5f
                || pod.fuel() != 3.25f
                || !pod.stranded()
                || pod.cargo().entries().size() != 1
                || pod.cargo().entries().getFirst().stack().getItem()
                        != OreRegistry.stack(OreType.IRONIUM).getItem()
                || pod.cargoUsed() != 1) {
            throw failure(
                    helper,
                    "the renamed pod must keep its chassis, hull, fuel, stranded flag and cargo, got %s hull %s fuel %s stranded %s cargo %s",
                    pod.chassis().id(),
                    pod.hull(),
                    pod.fuel(),
                    pod.stranded(),
                    pod.cargo().entries());
        }
        helper.succeed();
    }

    @GameTest
    public void aSavedEntityUnderAnIdNobodyRenamedIsStillDropped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Entity> loaded = loadAsAChunkDoes(level, savedPodUnder(level, "deepcharter:never_a_pod_type"));
        if (!loaded.isEmpty()) {
            throw failure(helper, "an unknown id outside the table must not load, got %s", loaded);
        }
        helper.succeed();
    }

    private static void requireRejected(GameTestHelper helper, String old, String current, String expected) {
        try {
            PodRenames.validate(Map.of(Identifier.parse(old), Identifier.parse(current)));
        } catch (IllegalStateException e) {
            if (!expected.equals(e.getMessage())) {
                throw failure(helper, "expected \"%s\", got \"%s\"", expected, e.getMessage());
            }
            return;
        }
        throw failure(helper, "the table entry %s -> %s must be rejected", old, current);
    }

    @GameTest
    public void aTableEntryThatCannotWorkIsRejectedNamingBothIds(GameTestHelper helper) {
        requireRejected(
                helper,
                "deepcharter:gone",
                "deepcharter:missing",
                "PodRenames: deepcharter:gone -> deepcharter:missing: new id is not a registered pod type");
        requireRejected(
                helper,
                "deepcharter:gone",
                "minecraft:pig",
                "PodRenames: deepcharter:gone -> minecraft:pig: new id is not a registered pod type");
        requireRejected(
                helper,
                "deepcharter:pod",
                "deepcharter:prospector",
                "PodRenames: deepcharter:pod -> deepcharter:prospector: the old id is still registered, so it is not renamed");
        helper.succeed();
    }
}
