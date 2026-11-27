package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.ore.DrilledMass;
import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFuelSlot;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Server GameTests for the pod's hold (#1270, ADR 0102): the saved format of version 2 keeps each stack in its slot
 * and refuses two stacks in one; {@code takeAll} empties every slot; and drilled ore carries the mass it was drilled at
 * on the stack itself ({@link DrilledMass}), wherever the stack goes.
 */
public class PodHoldTest {
    private static CompoundTag savedPod(ServerLevel level, PodEntity pod) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        pod.saveWithoutId(output);
        return output.buildResult();
    }

    private static void load(ServerLevel level, PodEntity pod, CompoundTag tag) {
        pod.cargo().load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), pod);
    }

    @GameTest
    public void aHoldWithAGapAndANonOreStackReloadsIntoTheSameSlots(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        PodEntity copy = helper.spawn(PodRegistry.POD, 4, 2, 2);
        try {
            pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
            pod.cargo().setAt(pod, 3, new ItemStack(Items.CHARCOAL, 20));
            float mass = pod.cargoMass();
            load(level, copy, savedPod(level, pod));
            expect(
                    helper,
                    copy.cargo().isReadable()
                            && copy.cargo().stackAt(0).is(OreRegistry.item(OreType.IRONIUM))
                            && copy.cargo().stackAt(1).isEmpty()
                            && copy.cargo().stackAt(2).isEmpty()
                            && copy.cargo().stackAt(3).is(Items.CHARCOAL)
                            && copy.cargo().stackAt(3).getCount() == 20
                            && copy.cargo().reach() == 4
                            && copy.cargoUsed() == 2
                            && copy.cargoMass() == mass,
                    "the hold should reload as ironium, two gaps and 20 charcoal, mass %s: got %s, %s, %s, %s, mass %s",
                    mass,
                    copy.cargo().stackAt(0),
                    copy.cargo().stackAt(1),
                    copy.cargo().stackAt(2),
                    copy.cargo().stackAt(3),
                    copy.cargoMass());
            helper.succeed();
        } finally {
            pod.discard();
            copy.discard();
        }
    }

    @GameTest
    public void twoStacksSavedInOneSlotLoadAsUnreadableAndAreWrittenBackUnchanged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        PodEntity copy = helper.spawn(PodRegistry.POD, 4, 2, 2);
        try {
            pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
            pod.cargo().setAt(pod, 1, new ItemStack(Items.DIRT, 5));
            CompoundTag tag = savedPod(level, pod);
            ListTag entries = tag.getListOrEmpty("cargo");
            ((CompoundTag) entries.get(1)).putInt("slot", 0);
            load(level, copy, tag);
            CompoundTag resaved = savedPod(level, copy);
            expect(
                    helper,
                    !copy.cargo().isReadable()
                            && copy.cargoUsed() == 0
                            && copy.cargo().stackAt(0).isEmpty()
                            && Objects.equals(resaved.get("cargo"), tag.get("cargo"))
                            && resaved.getIntOr("cargo_version", -1) == 2,
                    "two stacks in slot 0 must load as unreadable and save back unchanged, got %s, version %s",
                    resaved.get("cargo"),
                    resaved.get("cargo_version"));
            helper.succeed();
        } finally {
            pod.discard();
            copy.discard();
        }
    }

    @GameTest
    public void takeAllEmptiesEverySlotAndLeavesTheFuelSlot(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        try {
            pod.setFuel(PodTuning.DEFAULT.shell().fullFuel());
            pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
            pod.cargo().setAt(pod, 2, new ItemStack(Items.COBBLESTONE, 12));
            PodFuelSlot.container(pod).setItem(0, new ItemStack(Items.COAL, 3));
            List<ItemStack> taken = pod.cargo().takeAll(pod);
            expect(
                    helper,
                    taken.size() == 2
                            && taken.get(0).is(OreRegistry.item(OreType.IRONIUM))
                            && taken.get(1).is(Items.COBBLESTONE)
                            && taken.get(1).getCount() == 12
                            && pod.cargo().reach() == 0
                            && pod.cargoUsed() == 0
                            && pod.cargoMass() == 0f
                            && PodFuelSlot.container(pod).getItem(0).getCount() == 3,
                    "takeAll returns the ironium and the cobblestone, empties the hold and keeps the fuel: %s, %s used,"
                            + " fuel slot %s",
                    taken,
                    pod.cargoUsed(),
                    PodFuelSlot.container(pod).getItem(0));
            helper.succeed();
        } finally {
            pod.discard();
        }
    }

    @GameTest
    public void drilledOreCarriesItsMassOnTheStack(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        try {
            float drilled = 3f;
            expect(helper, OreType.GOLDIUM.mass() != drilled, "the test needs a mass other than goldium's own");
            pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM), drilled);
            float inHold = OreEncumbrance.massOf(pod.cargo().stackAt(0));
            RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            CompoundTag saved = (CompoundTag)
                    ItemStack.CODEC.encodeStart(ops, pod.cargo().stackAt(0)).getOrThrow();
            CompoundTag component =
                    saved.getCompoundOrEmpty("components").getCompoundOrEmpty("deepcharter:drilled_mass");
            // Pinned literally: the component's id, version 1, and the mass of one item by this name.
            expect(
                    helper,
                    component.getIntOr("version", -1) == 1 && component.getFloatOr("mass", -1f) == drilled,
                    "the drilled mass should save as deepcharter:drilled_mass, version 1, mass %s: %s",
                    drilled,
                    saved);
            List<ItemStack> taken = pod.cargo().takeAll(pod);
            float spilled = taken.isEmpty() ? -1f : OreEncumbrance.massOf(taken.getFirst());
            ItemStack plain = OreRegistry.stack(OreType.GOLDIUM);
            expect(
                    helper,
                    inHold == drilled && spilled == drilled && OreEncumbrance.massOf(plain) == OreType.GOLDIUM.mass(),
                    "goldium drilled at %s weighs that in the hold and out of it, and a plain one its own %s: %s, %s,"
                            + " %s",
                    drilled,
                    OreType.GOLDIUM.mass(),
                    inHold,
                    spilled,
                    OreEncumbrance.massOf(plain));
            helper.succeed();
        } finally {
            pod.discard();
        }
    }

    @GameTest
    public void aFutureDrilledMassWeighsTheOresOwnAndIsWrittenBackUnchanged(GameTestHelper helper) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ItemStack ore = OreRegistry.stack(OreType.GOLDIUM);
        ore.set(OreRegistry.DRILLED_MASS, new Versioned.Unreadable<>(UnreadableChecks.futureData()));
        Tag saved = ItemStack.CODEC.encodeStart(ops, ore).getOrThrow();
        ItemStack read = ItemStack.CODEC.parse(ops, saved).getOrThrow();
        Tag resaved = ItemStack.CODEC.encodeStart(ops, read).getOrThrow();
        expect(
                helper,
                OreEncumbrance.massOf(read) == OreType.GOLDIUM.mass() && saved.equals(resaved),
                "an unreadable drilled mass weighs goldium's own %s and saves back unchanged: weighs %s, %s then %s",
                OreType.GOLDIUM.mass(),
                OreEncumbrance.massOf(read),
                saved,
                resaved);
        helper.succeed();
    }
}
