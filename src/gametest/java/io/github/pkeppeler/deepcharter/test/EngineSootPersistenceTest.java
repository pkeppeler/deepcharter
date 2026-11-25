package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * The engine's soot is a persistent pod attachment (ADR 0097): it is saved with literal values, an unreadable one is
 * kept through a tick and a refuel, and it crosses a breach with the pod.
 */
public class EngineSootPersistenceTest {
    private static final String ATTACHMENTS_KEY = "fabric:attachments";
    private static final int CROSSING_TICKS = 200;

    /** Saves {@code pod}, discards it and loads a new pod from the data. */
    private static PodEntity reload(GameTestHelper helper, PodEntity pod) {
        ServerLevel level = helper.getLevel();
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        pod.saveWithoutId(output);
        pod.discard();
        Entity loaded = EntityType.create(
                        PodRegistry.POD,
                        TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()),
                        level,
                        EntitySpawnReason.LOAD)
                .orElseThrow(() -> failure(helper, "the saved pod did not load"));
        return (PodEntity) loaded;
    }

    private static CompoundTag savedSoot(GameTestHelper helper, PodEntity pod) {
        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        pod.saveWithoutId(output);
        return output.buildResult()
                .getCompound(ATTACHMENTS_KEY)
                .orElseThrow(() -> failure(helper, "the pod saved no attachments"))
                .getCompound(EngineSoot.STATE.identifier().toString())
                .orElseThrow(() -> failure(helper, "the pod saved no soot"));
    }

    @GameTest
    public void theSootStateIsSavedWithLiteralValuesAndLoadsBack(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        PodEntity loaded = null;
        try {
            pod.setAttached(EngineSoot.STATE, Versioned.of(new EngineSoot.State(7.5f, 2.25f, 1.125f)));
            CompoundTag saved = savedSoot(helper, pod);
            // Pinned literally: version 1, and the three fields by these names.
            expect(helper, saved.getIntOr("version", -1) == 1, "the soot should save as version 1, got %s", saved);
            expect(helper, saved.getFloatOr("soot", -1f) == 7.5f, "the saved soot should be 7.5, got %s", saved);
            expect(
                    helper,
                    saved.getFloatOr("tank_litres", -1f) == 2.25f,
                    "the saved tank litres should be 2.25: %s",
                    saved);
            expect(
                    helper,
                    saved.getFloatOr("tank_soot", -1f) == 1.125f,
                    "the saved tank soot should be 1.125: %s",
                    saved);
            loaded = reload(helper, pod);
            EngineSoot.State read = Versioned.orThrow(loaded, EngineSoot.STATE);
            expect(
                    helper,
                    read.equals(new EngineSoot.State(7.5f, 2.25f, 1.125f)),
                    "the soot should load as it was saved, got %s",
                    read);
            helper.succeed();
        } finally {
            if (loaded != null) {
                loaded.discard();
            }
        }
    }

    @GameTest
    public void anUnreadableSootStateIsKeptThroughATickAndARefuel(GameTestHelper helper) {
        String key = EngineSoot.STATE.identifier().toString();
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        MockPlayer player = MockPlayers.join(helper, "unreadable-sooter");
        LogCapture log = LogCapture.start(key);
        try {
            player.player().setGameMode(GameType.SURVIVAL);
            CompoundTag future = UnreadableChecks.futureData();
            future.putString("added-in-v99", "kept");
            pod.setAttached(EngineSoot.STATE, new Versioned.Unreadable<EngineSoot.State>(future));
            pod.setFuel(60f);
            // The real tick, with fuel in the tank, and a refuel with a solid fuel: neither may throw or write over it.
            for (int i = 0; i < 40; i++) {
                pod.tick();
            }
            player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHARCOAL, 4));
            UseEntity.on(player.player(), pod.level(), InteractionHand.MAIN_HAND, pod);
            expect(helper, EngineSoot.soot(pod) == 0f && !EngineSoot.isLimp(pod), "unreadable soot reads as none");
            expect(helper, !EngineSoot.isReadable(pod), "the state should still be unreadable");
            try {
                EngineSoot.service(pod);
                throw failure(helper, "servicing an unreadable state must fail rather than overwrite it");
            } catch (IllegalStateException expected) {
                expect(
                        helper,
                        String.valueOf(expected.getMessage()).contains(key),
                        "the failure should name the attachment, got: %s",
                        expected.getMessage());
            }
            expect(
                    helper,
                    future.equals(savedSoot(helper, pod)),
                    "the unreadable data was not written back unchanged: %s",
                    savedSoot(helper, pod));
            expect(helper, log.errors().size() == 1, "the unreadable soot should be logged once, was %s", log.errors());
            helper.succeed();
        } finally {
            player.leave();
            pod.discard();
        }
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void theSootCrossesABreachWithThePod(GameTestHelper helper) {
        double x = 2100.5;
        double z = 2100.5;
        ServerLevel one = TestLayers.level(helper, 1);
        BlockPos column = BlockPos.containing(x, 0, z);
        RoomCarver.carvePocketBelow(one, 1, column.getX() - 1, column.getX() + 1, column.getZ() - 1, column.getZ() + 1);
        RoomCarver.carve(
                one,
                column.offset(-1, 0, -1).atY(TestLayers.band(one, 1).minY()),
                column.offset(1, 0, 1).atY(TestLayers.band(one, 1).minY() + 10),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        MockPlayer mock = MockPlayers.join(helper, "soot-crossing");
        mock.teleportTo(one, TestLayers.vec(1, x, 8, z), 0, 0);
        PodEntity[] pod = {null};
        FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, TestLayers.worldY(1, 8), z), () -> {
            pod[0] = TestEntities.create(PodRegistry.POD, one);
            pod[0].setPos(x, TestLayers.worldY(1, 8), z);
            one.addFreshEntity(pod[0]);
            // No solid fuel in the tank, so the fall's burn writes nothing and the values stay literal.
            pod[0].setAttached(EngineSoot.STATE, Versioned.of(new EngineSoot.State(7.5f, 0f, 0f)));
            if (!mock.player().startRiding(pod[0], true, false)) {
                throw failure(helper, "the mock could not board the pod");
            }
        });
        helper.succeedWhen(() -> {
            expect(helper, pod[0] != null, "waiting for the chunk to tick entities");
            ServerPlayer player = mock.player();
            expect(
                    helper,
                    TestLayers.isIn(player, 2),
                    "the rider is still in %s",
                    player.level().dimension());
            if (!(player.getVehicle() instanceof PodEntity arrived) || arrived.isRemoved()) {
                throw failure(helper, "the rider is on %s after crossing, not a pod", player.getVehicle());
            }
            EngineSoot.State read = Versioned.orThrow(arrived, EngineSoot.STATE);
            expect(
                    helper,
                    read.equals(new EngineSoot.State(7.5f, 0f, 0f)),
                    "the soot should cross with the pod, got %s",
                    read);
        });
    }
}
