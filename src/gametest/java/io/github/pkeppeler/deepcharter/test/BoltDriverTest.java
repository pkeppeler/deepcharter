package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.arm;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.bolts;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.charter;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.corridor;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.cut;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.figure;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.fire;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.setBolt;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.told;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.walker;
import static io.github.pkeppeler.deepcharter.test.support.BoltRoom.withPvp;
import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.layer.CaveInTuning;
import io.github.pkeppeler.deepcharter.layer.CaveIns;
import io.github.pkeppeler.deepcharter.layer.CaveInsProbe;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.BoltDriverTuning;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.test.support.BoltRoom.Corridor;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.UnloadedChunk;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for the bolt driver (#987): what it fires, what it never touches, the roof bolts it sets, and what a roof bolt does in
 * a cave-in. Each test has its own chunk of a layer 1 corridor, 2 blocks high and 1 wide, in a bed of stone: the roof is the row of stone
 * over it. The cave-in's own rules (the creak before the fall, a blast that starts one) are here too, because the bolt is their answer.
 */
public class BoltDriverTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoltDriverTest.class);
    private static final String ALONE = "deepcharter-test:alone";
    private static final CaveInTuning CAVE_IN = CaveInTuning.DEFAULT;
    private static final BoltDriverTuning DRIVER = BoltDriverTuning.DEFAULT;
    private static final int LENGTH = 14;
    /** Two corridor slots no other test uses. */
    private static final int SPARE_SLOT = 40;

    private static final float EAST = -90f;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1200;
    /**
     * How long a test waits for a joined mock to settle. 26.3 has no spawn timer: a player is invulnerable only until its client has
     * loaded or while it changes dimension, and a mock's client loads a few ticks after the join.
     */
    private static final int SPAWN_PROTECTION_TICKS = 100;

    /** How long a test waits for a mock that reads as hittable to be hit. */
    private static final int PROTECTION_RETRY_TICKS = 100;

    /** A mock reads what the server sends it on its own tick, a tick or two later. */
    private static final int MESSAGE_TICKS = 3;

    /** The rock of a cave-in falls two blocks and comes to rest well within this. */
    private static final int ROCK_SETTLES_TICKS = 40;

    private static final Identifier CREAK = SoundEvents.CREAKING_TWITCH.location();

    @GameTest
    public void theIdsArePermanentAndTheDriverIsKeptNotSpent(GameTestHelper helper) {
        String block = BuiltInRegistries.BLOCK.getKey(LayerBlocks.ROOF_BOLT).toString();
        if (!block.equals("deepcharter:roof_bolt")) {
            throw failure(helper, "a roof bolt stays in a saved world: its id is permanent, it is %s", block);
        }
        if (!Consumable.BOLT_DRIVER.itemId().toString().equals("deepcharter:bolt_driver")
                || !Consumable.BOLT.itemId().toString().equals("deepcharter:bolt")) {
            throw failure(
                    helper, "the item ids are %s and %s", Consumable.BOLT_DRIVER.itemId(), Consumable.BOLT.itemId());
        }
        expect(
                helper,
                Consumable.BOLT_DRIVER.kind() == Consumable.Kind.TOOL
                        && Consumable.BOLT.kind() == Consumable.Kind.CONSUMED,
                "the driver is a kept tool and the bolts are consumed, they are %s and %s",
                Consumable.BOLT_DRIVER.kind(),
                Consumable.BOLT.kind());
        if (RepairRegistry.item(Consumable.BOLT_DRIVER).getDefaultInstance().getMaxStackSize() != 1) {
            throw failure(helper, "the driver is one tool, not a stack");
        }
        if (Consumable.BOLT.perPurchase() != 8 || Consumable.BOLT_DRIVER.perPurchase() != 1) {
            throw failure(
                    helper,
                    "a pack is 8 bolts and a driver is 1, the shop gives %d and %d",
                    Consumable.BOLT.perPurchase(),
                    Consumable.BOLT_DRIVER.perPurchase());
        }
        if (CAVE_IN.warnTicks() < 60) {
            throw failure(
                    helper,
                    "a cave-in warns at least 3 s (60 ticks) before it falls, it warns %d",
                    CAVE_IN.warnTicks());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aShotSpendsOneBoltAndHurtsACreatureAtRange(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 0);
        MockPlayer shooter = walker(helper, corridor, "bolt-shooter", 1, EAST);
        arm(shooter.player(), 3);
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            LamplessFigure near = figure(corridor.level(), corridor.stand(11));
            float before = near.getHealth();
            if (!fire(shooter.player()).consumesAction()) {
                throw failure(helper, "a shot with a bolt in the pack should be taken");
            }
            if (bolts(shooter.player()) != 2) {
                throw failure(helper, "a shot costs one bolt, 3 became %d", bolts(shooter.player()));
            }
            if (Math.abs(before - near.getHealth() - DRIVER.damage()) > 0.01) {
                throw failure(
                        helper,
                        "a bolt takes %.1f health from a creature 10 blocks off, it took %.1f",
                        DRIVER.damage(),
                        before - near.getHealth());
            }
            // The driver rests between shots.
            if (fire(shooter.player()).consumesAction() && bolts(shooter.player()) == 2) {
                throw failure(helper, "a shot spends a bolt");
            }
            near.discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theDriverRestsBetweenShots(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 1);
        MockPlayer shooter = walker(helper, corridor, "bolt-rest", 1, EAST);
        arm(shooter.player(), 3);
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            fire(shooter.player());
            // No cooldown clearing: a second right click in the same tick is the driver's to refuse.
            shooter.player()
                    .gameMode
                    .useItem(
                            shooter.player(),
                            shooter.player().level(),
                            shooter.player().getItemInHand(InteractionHand.MAIN_HAND),
                            InteractionHand.MAIN_HAND);
            if (bolts(shooter.player()) != 2) {
                throw failure(
                        helper,
                        "two clicks in one tick should spend one bolt (cooldown %d ticks), 3 became %d",
                        DRIVER.fireCooldownTicks(),
                        bolts(shooter.player()));
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aBoltThatFliesFartherThanItsRangeHitsNothing(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 2);
        MockPlayer shooter = walker(helper, corridor, "bolt-range", 0, EAST);
        arm(shooter.player(), 2);
        // The corridor is 14 long, so the target stands in a second, longer one: the range itself is what is under
        // test.
        int far = (int) Math.ceil(DRIVER.range()) + 4;
        int y = corridor.floor(0).getY();
        int z = corridor.floor(0).getZ();
        RoomCarver.carve(corridor.level(), corridor.x(), corridor.x() + far, y, y + 1, z, z, Blocks.AIR);
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            LamplessFigure target = figure(corridor.level(), corridor.stand(far - 1));
            fire(shooter.player());
            if (target.getHealth() < target.getMaxHealth()) {
                throw failure(helper, "a target %d blocks away is past the range of %.0f", far - 1, DRIVER.range());
            }
            target.discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aBoltBreaksNothingThatPeopleBuilt(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 3);
        MockPlayer shooter = walker(helper, corridor, "bolt-grief", 0, EAST);
        arm(shooter.player(), 6);
        List<BlockState> built = List.of(
                Blocks.GLASS.defaultBlockState(),
                Blocks.OAK_PLANKS.defaultBlockState(),
                Blocks.TORCH.defaultBlockState(),
                Blocks.CHEST.defaultBlockState());
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            LamplessFigure behind = figure(corridor.level(), corridor.stand(LENGTH - 1));
            for (int i = 0; i < built.size(); i++) {
                BlockPos at = corridor.floor(4 + i * 2);
                corridor.level().setBlock(at, built.get(i), Block.UPDATE_ALL);
                corridor.level().setBlock(at.above(), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            }
            for (int shot = 0; shot < built.size(); shot++) {
                fire(shooter.player());
            }
            for (int i = 0; i < built.size(); i++) {
                BlockPos at = corridor.floor(4 + i * 2);
                if (!corridor.level().getBlockState(at).equals(built.get(i))
                        || !corridor.level().getBlockState(at.above()).is(Blocks.GLASS)) {
                    throw failure(helper, "a bolt broke or changed a placed block at %s", at);
                }
            }
            if (behind.getHealth() < behind.getMaxHealth()) {
                throw failure(helper, "a bolt flew through a wall of placed blocks to the creature behind it");
            }
            behind.discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void anEmptyDriverFiresNothingAndSays(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 4);
        MockPlayer shooter = walker(helper, corridor, "bolt-empty", 1, EAST);
        arm(shooter.player(), 0);
        LamplessFigure[] target = {null};
        long[] actedAt = {0};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            if (target[0] == null) {
                target[0] = figure(corridor.level(), corridor.stand(8));
                if (fire(shooter.player()).consumesAction() || target[0].getHealth() < target[0].getMaxHealth()) {
                    throw failure(helper, "a driver with no bolts fires nothing");
                }
                actedAt[0] = helper.getTick();
                return;
            }
            if (helper.getTick() < actedAt[0] + MESSAGE_TICKS) {
                return;
            }
            if (!told(shooter, "message.deepcharter.repair.bolt_none")) {
                throw failure(helper, "a driver with no bolts says so, it said %s", shooter.actionBarMessages());
            }
            target[0].discard();
            helper.succeed();
        });
    }

    /**
     * A test that cuts its corridor after its neighbour must not fill the neighbour's first cell, where its shooter stands: the shooter's
     * eye would sit in rock and no bolt would fly (#1065). Two spare slots, cut in the order that clobbers. This guards the stride, not the
     * general rule; the bed-holds-air gate of #1090 supersedes it, and then this test and {@code SPARE_SLOT} are deleted.
     */
    @GameTest
    public void aCorridorCutAfterItsNeighbourLeavesTheNeighboursFirstCellOpen(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 1);
        Corridor later = cut(level, LENGTH, SPARE_SLOT + 1);
        cut(level, LENGTH, SPARE_SLOT);
        BlockPos cell = later.floor(0).above();
        expect(
                helper,
                level.getBlockState(cell).isAir(),
                "the first cell of a corridor, at eye height, was %s after the corridor before it was cut",
                level.getBlockState(cell));
        helper.succeed();
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aBoltHurtsAnotherPlayerOnlyWhenPvpIsOn(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 5);
        MockPlayer shooter = walker(helper, corridor, "bolt-pvp-shooter", 0, EAST);
        MockPlayer other = walker(helper, corridor, "bolt-pvp-other", 9, 90f);
        MinecraftServer server = helper.getLevel().getServer();
        int[] misses = {0};
        String[] lastTry = {"no shot yet"};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            ServerPlayer target = other.player();
            // A joined player takes no damage until its client has loaded: wait for the target to be hittable, which
            // includes being alive, in the corridor's level and in a chunk that ticks entities, as a bolt's sweep
            // needs. A precondition that still fails when the retry window ends fails with the state dump.
            boolean retrying = helper.getTick() < SPAWN_PROTECTION_TICKS + PROTECTION_RETRY_TICKS;
            if (!hittable(target, corridor.level())) {
                if (retrying) {
                    return;
                }
                throw failure(
                        helper,
                        "the target was never hittable, so no bolt was tried: %s",
                        state(target, shooter.player(), corridor.level()));
            }
            // Two bolts a try, and a full pack each try: the retry window below is ticks, not however many bolts a pack
            // holds.
            arm(shooter.player(), 2);
            // A full stomach heals: keep it low, so a loss is the bolt's.
            target.getFoodData().setFoodLevel(6);
            boolean was = server.getGameRules().get(GameRules.PVP);
            float health = target.getHealth();
            try {
                server.getGameRules().set(GameRules.PVP, false, server);
                fire(shooter.player());
                if (target.getHealth() < health) {
                    throw failure(helper, "a bolt hurt a player on a server with PvP off");
                }
                server.getGameRules().set(GameRules.PVP, true, server);
                InteractionResult result = fire(shooter.player());
                lastTry[0] = shot(shooter, target, corridor.level(), result);
                if (target.getHealth() >= health) {
                    // A try can miss while the target is still settling: try again, and dump the state and the ray if
                    // none hits.
                    if (retrying) {
                        misses[0]++;
                        return;
                    }
                    throw failure(
                            helper,
                            "a bolt spared a player on a server with PvP on after %s tries: %s; last shot: %s",
                            misses[0] + 1,
                            state(target, shooter.player(), corridor.level()),
                            lastTry[0]);
                }
                if (misses[0] > 0) {
                    LOGGER.info("a bolt hurt the player on try {}, after {} misses (#1065)", misses[0] + 1, misses[0]);
                }
            } finally {
                server.getGameRules().set(GameRules.PVP, was, server);
            }
            helper.succeed();
        });
    }

    /** What the last PvP-on shot did: the driver's answer and the ray it fires, so a miss names its cause (#1065). */
    private static String shot(MockPlayer shooter, ServerPlayer target, ServerLevel level, InteractionResult result) {
        ServerPlayer from = shooter.player();
        Vec3 eye = from.getEyePosition();
        Vec3 end = eye.add(from.getLookAngle().scale(DRIVER.range()));
        HitResult block =
                level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, from));
        List<Component> said = shooter.actionBarMessages();
        return ("driver returned %s, said %s, rest on the driver %s, eye %s pitch %s, ray hit %s at %s (%s), "
                        + "target box %s, target health %s, target pose %s, game modes shooter %s target %s, "
                        + "target abilities invulnerable %s, target hurt by a player's attack: invulnerable %s, "
                        + "may be hit by a projectile %s, shooter may harm it %s, invulnerable time %s")
                .formatted(
                        result,
                        said.isEmpty() ? "nothing" : said.getLast().getString(),
                        from.getCooldowns().getCooldownPercent(from.getItemInHand(InteractionHand.MAIN_HAND), 0f),
                        eye,
                        from.getXRot(),
                        block.getType(),
                        block.getLocation(),
                        level.getBlockState(BlockPos.containing(block.getLocation())),
                        target.getBoundingBox(),
                        target.getHealth(),
                        target.getPose(),
                        from.gameMode.getGameModeForPlayer(),
                        target.gameMode.getGameModeForPlayer(),
                        target.getAbilities().invulnerable,
                        target.isInvulnerableTo(level, level.damageSources().playerAttack(from)),
                        target.canBeHitByProjectile(),
                        from.canHarmPlayer(target),
                        target.getInvulnerableTime());
    }

    private static boolean hittable(ServerPlayer target, ServerLevel level) {
        return target.isAlive()
                && target.level() == level
                && level.isPositionEntityTicking(target.blockPosition())
                && !target.isInvulnerableTo(level, level.damageSources().generic());
    }

    private static String state(ServerPlayer target, ServerPlayer shooter, ServerLevel level) {
        return "target at %s alive %s in level %s entity-ticking %s invulnerable %s loaded %s health %s hurt cooldown %s, shooter at %s facing %s with %s bolts, living entities within the driver's range box %s"
                .formatted(
                        target.position(),
                        target.isAlive(),
                        target.level() == level,
                        level.isPositionEntityTicking(target.blockPosition()),
                        target.isInvulnerableTo(level, level.damageSources().generic()),
                        target.connection.hasClientLoaded(),
                        target.getHealth(),
                        target.damageCooldownTime,
                        shooter.position(),
                        shooter.getYRot(),
                        bolts(shooter),
                        level.getEntitiesOfClass(
                                LivingEntity.class,
                                shooter.getBoundingBox().inflate(DRIVER.range()),
                                entity -> entity != shooter));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theDriverIsNotUsedFromAPodSeat(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 6);
        MockPlayer pilot = walker(helper, corridor, "bolt-pilot", 1, EAST);
        arm(pilot.player(), 3);
        long[] actedAt = {-1};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            if (actedAt[0] < 0) {
                PodEntity pod = TestEntities.create(PodRegistry.POD, corridor.level());
                pod.setPos(corridor.stand(1));
                corridor.level().addFreshEntity(pod);
                if (!pilot.player().startRiding(pod, true, false)) {
                    throw failure(helper, "the pilot could not mount the pod");
                }
                InteractionResult fired = fire(pilot.player());
                InteractionResult set = setBolt(pilot.player(), corridor.roof(1));
                pilot.player().stopRiding();
                pod.discard();
                if (fired.consumesAction() || set.consumesAction() || bolts(pilot.player()) != 3) {
                    throw failure(helper, "a pilot in the seat neither fires nor sets a bolt, and keeps all 3 bolts");
                }
                if (corridor.level().getBlockState(corridor.roof(1).below()).is(LayerBlocks.ROOF_BOLT)) {
                    throw failure(helper, "a pilot in the seat set a roof bolt");
                }
                actedAt[0] = helper.getTick();
                return;
            }
            if (helper.getTick() < actedAt[0] + MESSAGE_TICKS) {
                return;
            }
            if (!told(pilot, "message.deepcharter.repair.bolt_on_foot")) {
                throw failure(helper, "a pilot is told the driver is for on foot, got %s", pilot.actionBarMessages());
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theDriverSetsARoofBoltInBareRockOverhead(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 7);
        MockPlayer setter = walker(helper, corridor, "bolt-setter", 5, EAST);
        arm(setter.player(), 4);
        long[] actedAt = {-1};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            if (actedAt[0] >= 0) {
                if (helper.getTick() >= actedAt[0] + MESSAGE_TICKS) {
                    if (!told(setter, "message.deepcharter.repair.bolt_already")
                            || !told(setter, "message.deepcharter.repair.bolt_set")
                            || !told(setter, "message.deepcharter.repair.bolt_not_rock")) {
                        throw failure(
                                helper,
                                "the driver says what it did: set, already held, not rock; got %s",
                                setter.actionBarMessages());
                    }
                    helper.succeed();
                }
                return;
            }
            BlockPos roof = corridor.roof(5);
            BlockPos cell = roof.below();
            InteractionResult set = setBolt(setter.player(), roof);
            if (!set.consumesAction()) {
                throw failure(
                        helper, "the driver should set a roof bolt against the underside of bare rock, got %s", set);
            }
            if (!corridor.level().getBlockState(cell).is(LayerBlocks.ROOF_BOLT)
                    || !CaveIns.boltNear(corridor.level(), roof, CAVE_IN.boltReach())) {
                throw failure(
                        helper,
                        "a roof bolt should hang under the roof, found %s",
                        corridor.level().getBlockState(cell));
            }
            if (bolts(setter.player()) != 3) {
                throw failure(helper, "a roof bolt costs one bolt, 4 became %d", bolts(setter.player()));
            }
            // Too close to the first: it would hold nothing new.
            if (setBolt(setter.player(), corridor.roof(6)).consumesAction() || bolts(setter.player()) != 3) {
                throw failure(helper, "a second bolt next to the first holds nothing new and is refused");
            }
            // Placed blocks are not bare rock: there is nothing to bolt to.
            BlockPos planks = corridor.roof(11);
            corridor.level().setBlock(planks, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            if (setBolt(setter.player(), planks).consumesAction()
                    || !corridor.level().getBlockState(planks.below()).isAir()) {
                throw failure(helper, "a bolt is set in natural rock only");
            }
            actedAt[0] = helper.getTick();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aRoofBoltGoesWithTheRoofItHangsFromAndDropsNothing(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 8);
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent()) {
                return;
            }
            BlockPos cell = corridor.roof(4).below();
            corridor.level().setBlock(cell, LayerBlocks.ROOF_BOLT.defaultBlockState(), Block.UPDATE_ALL);
            if (!corridor.level().getBlockState(cell).is(LayerBlocks.ROOF_BOLT)) {
                throw failure(helper, "a roof bolt stands where the roof can hold it");
            }
            corridor.level().destroyBlock(corridor.roof(4), false);
            if (!corridor.level().getBlockState(cell).isAir()) {
                throw failure(
                        helper,
                        "a roof bolt cannot hang from nothing, found %s",
                        corridor.level().getBlockState(cell));
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void unboltedRoofFallsAfterACreakAndTheBoltedStretchStands(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 9);
        MockPlayer under = walker(helper, corridor, "bolt-under", 7, EAST);
        arm(under.player(), 2);
        int radius = CAVE_IN.stretchRadius();
        long[] startedAt = {-1};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            ServerLevel level = corridor.level();
            long now = level.getGameTime();
            if (startedAt[0] < 0) {
                if (!CaveIns.shake(level, corridor.floor(7))) {
                    throw failure(helper, "a roof of bare rock over a person is loose: the cave-in should start");
                }
                startedAt[0] = now;
                return;
            }
            long elapsed = now - startedAt[0];
            if (elapsed == CAVE_IN.warnTicks() - 2) {
                for (int along = 7 - radius; along <= 7 + radius; along++) {
                    if (!corridor.standing(along)) {
                        throw failure(
                                helper,
                                "the roof at %d fell %d ticks early, it warns for %d",
                                along,
                                CAVE_IN.warnTicks() - elapsed,
                                CAVE_IN.warnTicks());
                    }
                }
                if (!under.soundsHeard().contains(CREAK)) {
                    throw failure(helper, "the roof creaks before it falls, and nobody under it heard it");
                }
            }
            if (elapsed == CAVE_IN.warnTicks() + 3) {
                for (int along = 7 - radius; along <= 7 + radius; along++) {
                    if (corridor.standing(along)) {
                        throw failure(helper, "the loose roof at %d should have fallen by now", along);
                    }
                }
                if (!corridor.standing(7 + radius + 1)) {
                    throw failure(helper, "a cave-in takes the stretch round the person and no more");
                }
            }
            if (elapsed == CAVE_IN.warnTicks() + ROCK_SETTLES_TICKS) {
                int lying = 0;
                for (int along = 7 - radius; along <= 7 + radius; along++) {
                    if (corridor.level().getBlockState(corridor.floor(along)).is(Blocks.STONE)) {
                        lying++;
                    }
                }
                if (lying == 0) {
                    throw failure(helper, "the roof fell as rock, which lies where it landed: none lies in the tunnel");
                }
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aBoltedRoofHoldsWhereTheBoltReachesAndNoFarther(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 10);
        MockPlayer under = walker(helper, corridor, "bolt-holds", 7, EAST);
        arm(under.player(), 2);
        int radius = CAVE_IN.stretchRadius();
        long[] startedAt = {-1};
        float[] health = {0};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            ServerLevel level = corridor.level();
            long now = level.getGameTime();
            if (startedAt[0] < 0) {
                if (!setBolt(under.player(), corridor.roof(7)).consumesAction()) {
                    throw failure(helper, "the bolt should be set");
                }
                health[0] = under.player().getHealth();
                if (!CaveIns.shake(level, corridor.floor(7))) {
                    throw failure(helper, "the roof beyond the bolt's reach is still loose: the cave-in should start");
                }
                startedAt[0] = now;
                return;
            }
            under.player().getFoodData().setFoodLevel(6);
            if (now - startedAt[0] == CAVE_IN.warnTicks() + 3) {
                int held = 0;
                int fell = 0;
                for (int along = 7 - radius; along <= 7 + radius; along++) {
                    double distance = Math.sqrt((along - 7) * (along - 7) + 1.0);
                    boolean stands = corridor.standing(along);
                    if (distance <= CAVE_IN.boltReach()) {
                        held++;
                        if (!stands) {
                            throw failure(
                                    helper, "the roof at %d is %.2f from the bolt and should hold", along, distance);
                        }
                    } else {
                        fell++;
                        if (stands) {
                            throw failure(
                                    helper,
                                    "the roof at %d is %.2f from the bolt and should have fallen",
                                    along,
                                    distance);
                        }
                    }
                }
                if (held == 0 || fell == 0) {
                    throw failure(helper, "the test wants roof both held (%d) and loose (%d)", held, fell);
                }
                if (under.player().getHealth() < health[0]) {
                    throw failure(helper, "the person under the bolt was hurt by the fall");
                }
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aBoltSetWhileTheRoofCreaksSavesIt(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 11);
        MockPlayer under = walker(helper, corridor, "bolt-late", 7, EAST);
        arm(under.player(), 5);
        int radius = CAVE_IN.stretchRadius();
        long[] startedAt = {-1};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            ServerLevel level = corridor.level();
            long now = level.getGameTime();
            if (startedAt[0] < 0) {
                CaveIns.shake(level, corridor.floor(7));
                startedAt[0] = now;
                return;
            }
            long elapsed = now - startedAt[0];
            if (elapsed == 10) {
                // The creak is the warning: bolt the stretch before it falls, one bolt per reach.
                for (int along : new int[] {4, 7, 10}) {
                    under.teleportTo(level, corridor.stand(along), EAST, 0f);
                    setBolt(under.player(), corridor.roof(along));
                }
            }
            if (elapsed == CAVE_IN.warnTicks() + MESSAGE_TICKS + 2) {
                for (int along = 7 - radius; along <= 7 + radius; along++) {
                    if (!corridor.standing(along)) {
                        throw failure(helper, "the roof at %d fell although the stretch was bolted in time", along);
                    }
                }
                if (!told(under, "deepcharter.layer.cave_in_holds")) {
                    throw failure(helper, "a roof that holds says so, got %s", under.actionBarMessages());
                }
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aBlastShakesTheRoofOverAPersonOnFootButNotOverARiderOrAFarPerson(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 12);
        walker(helper, corridor, "bolt-blast-near", 3, EAST);
        MockPlayer rider = walker(helper, corridor, "bolt-blast-rider", 11, EAST);
        long[] startedAt = {-1};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            ServerLevel level = corridor.level();
            long now = level.getGameTime();
            if (startedAt[0] < 0) {
                PodEntity pod = TestEntities.create(PodRegistry.POD, level);
                pod.setPos(corridor.stand(11));
                level.addFreshEntity(pod);
                rider.player().startRiding(pod, true, false);
                withPvp(
                        helper.getLevel().getServer(),
                        true,
                        () -> CaveIns.blasted(level, point(corridor.stand(5).add(0, 1, 0)), Optional.empty()));
                startedAt[0] = now;
                return;
            }
            if (now - startedAt[0] == CAVE_IN.warnTicks() + 3) {
                if (corridor.standing(3)) {
                    throw failure(helper, "a blast 2 blocks from a person on foot should bring their roof down");
                }
                if (!corridor.standing(11)) {
                    throw failure(helper, "the roof over a person in a pod seat is not shaken: the pod is its cover");
                }
                helper.succeed();
            }
        });
    }

    /**
     * The shake's reach is counted from the edge of the rock the blast broke, not from its middle (#1458): a plastic explosives blast shakes a
     * person who is more than {@code shakeReach} from its middle but within it of its edge, and not one beyond it of its edge.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aBiggerBlastShakesFartherBecauseTheReachIsCountedFromItsEdge(GameTestHelper helper) {
        Corridor corridor = corridor(helper, 20, 44);
        int radius = RepairTuning.DEFAULT.plasticRadius();
        int near = 2 + radius + (int) CAVE_IN.shakeReach() - 2;
        int far = near + 4;
        walker(helper, corridor, "bolt-edge-near", near, EAST);
        walker(helper, corridor, "bolt-edge-far", far, EAST);
        BlockPos middle = corridor.floor(1).above();
        AABB blast = new AABB(
                middle.getX() - radius,
                middle.getY() - radius,
                middle.getZ() - radius,
                middle.getX() + radius + 1,
                middle.getY() + radius + 1,
                middle.getZ() + radius + 1);
        if (blast.getCenter().distanceTo(corridor.stand(near)) <= CAVE_IN.shakeReach()) {
            throw failure(
                    helper,
                    "the near person must stand beyond the reach of the blast's middle, or this proves nothing");
        }
        long[] startedAt = {-1};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            ServerLevel level = corridor.level();
            long now = level.getGameTime();
            if (startedAt[0] < 0) {
                withPvp(helper.getLevel().getServer(), true, () -> CaveIns.blasted(level, blast, Optional.empty()));
                startedAt[0] = now;
                return;
            }
            if (now - startedAt[0] == CAVE_IN.warnTicks() + 3) {
                expect(
                        helper,
                        !corridor.standing(near),
                        "a person within %.0f blocks of a blast's edge is shaken, though farther from its middle",
                        CAVE_IN.shakeReach());
                expect(
                        helper,
                        corridor.standing(far),
                        "a person more than %.0f blocks from a blast's edge is not shaken",
                        CAVE_IN.shakeReach());
                helper.succeed();
            }
        });
    }

    /** A blast that broke no rock: a point, as a test that only cares where the blast was passes it. */
    private static AABB point(Vec3 at) {
        return new AABB(at, at);
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void dynamiteFromAPodShakesTheRoofOverAPersonOnFoot(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 13);
        MockPlayer pilot = walker(helper, corridor, "bolt-dynamite-pilot", 11, 90f);
        walker(helper, corridor, "bolt-dynamite-foot", 3, EAST);
        long[] startedAt = {-1};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            ServerLevel level = corridor.level();
            long now = level.getGameTime();
            if (startedAt[0] < 0) {
                PodEntity pod = TestEntities.create(PodRegistry.POD, level);
                pod.setPos(corridor.stand(11));
                level.addFreshEntity(pod);
                pilot.player().startRiding(pod, true, false);
                pilot.player()
                        .getInventory()
                        .setItem(
                                pilot.player().getInventory().getSelectedSlot(),
                                new ItemStack(RepairRegistry.item(Consumable.DYNAMITE)));
                withPvp(helper.getLevel().getServer(), true, () -> {
                    expect(
                            helper,
                            pilot.player()
                                    .gameMode
                                    .useItem(
                                            pilot.player(),
                                            level,
                                            pilot.player().getItemInHand(InteractionHand.MAIN_HAND),
                                            InteractionHand.MAIN_HAND)
                                    .consumesAction(),
                            "the pilot's dynamite should go off");
                });
                startedAt[0] = now;
                return;
            }
            if (now - startedAt[0] == CAVE_IN.warnTicks() + 3) {
                if (corridor.standing(3)) {
                    throw failure(helper, "dynamite 8 blocks from a person on foot should shake their roof down");
                }
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aRoofBoltSurvivesSavingAndLoadingItsChunk(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 1);
        Corridor corridor = cut(level, LENGTH, 14);
        BlockPos roof = corridor.roof(5);
        BlockPos cell = roof.below();
        // The chunk is saved when it stops being forced and read back when it is forced again.
        UnloadedChunk.cycle(
                helper,
                level,
                roof,
                new UnloadedChunk.Stages(
                        () -> level.setBlock(cell, LayerBlocks.ROOF_BOLT.defaultBlockState(), Block.UPDATE_ALL),
                        () -> {},
                        () -> level.getChunkSource().hasChunk(cell.getX() >> 4, cell.getZ() >> 4),
                        () -> "the corridor's chunk",
                        () -> {
                            if (!level.getBlockState(cell).is(LayerBlocks.ROOF_BOLT)
                                    || !CaveIns.boltNear(level, roof, CAVE_IN.boltReach())) {
                                throw failure(
                                        helper,
                                        "a roof bolt was lost across a save and a load: %s",
                                        level.getBlockState(cell));
                            }
                        }));
    }

    // A stop empties every cave-in of the server, so no other test may be running its own: this is a batch of its own.
    @GameTest(environment = ALONE, maxTicks = MAX_TICKS)
    public void aStoppedServerForgetsACaveInThatWasCreaking(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 24);
        walker(helper, corridor, "bolt-stopped", 7, EAST);
        long[] startedAt = {-1};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            long now = corridor.level().getGameTime();
            if (startedAt[0] < 0) {
                expect(
                        helper,
                        CaveIns.shake(corridor.level(), corridor.floor(7)),
                        "the roof over a person is loose: the cave-in should start");
                // The static map outlives a server in one JVM: a stop must empty it.
                CaveInsProbe.stopped();
                startedAt[0] = now;
                return;
            }
            if (now - startedAt[0] == CAVE_IN.warnTicks() + 3) {
                expect(helper, corridor.standing(7), "a cave-in that was creaking when the server stopped fell later");
                expect(
                        helper,
                        CaveIns.shake(corridor.level(), corridor.floor(7)),
                        "after a stop the same place can creak again: nothing stale blocks it");
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPersonWhoIgnoresTheCreakIsHurtWithinTheCapAndNeitherKilledNorEntombed(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 25);
        MockPlayer under = walker(helper, corridor, "bolt-ignores", 7, EAST);
        long[] startedAt = {-1};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            ServerPlayer player = under.player();
            // A full stomach heals: keep it low, so a loss is the rock's.
            player.getFoodData().setFoodLevel(6);
            ServerLevel level = corridor.level();
            long now = level.getGameTime();
            if (startedAt[0] < 0) {
                expect(helper, player.getHealth() == player.getMaxHealth(), "the person starts at full health");
                expect(
                        helper,
                        CaveIns.shake(level, corridor.floor(7)),
                        "the roof over a person is loose: the cave-in should start");
                startedAt[0] = now;
                return;
            }
            if (now - startedAt[0] != CAVE_IN.warnTicks() + ROCK_SETTLES_TICKS) {
                return;
            }
            float lost = player.getMaxHealth() - player.getHealth();
            expect(helper, player.isAlive(), "a cave-in costs you, it does not kill a person at full health");
            expect(helper, lost > 0, "the rock that fell on a person who stood under it should hurt");
            expect(
                    helper,
                    lost <= CAVE_IN.fallDamageMax(),
                    "one cave-in cost %.1f health, the cap is %d",
                    lost,
                    CAVE_IN.fallDamageMax());
            expect(
                    helper,
                    level.noBlockCollision(player, player.getBoundingBox()) && !player.isInWall(),
                    "the person is inside rock after the cave-in: %s",
                    level.getBlockState(player.blockPosition()));
            double before = player.getY();
            player.move(MoverType.SELF, new Vec3(0, 0.2, 0));
            expect(helper, player.getY() > before, "the person is wedged and cannot move up out of the rubble");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void withPvpOffABlastShakesOnlyTheRoofOfItsOwnCharter(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 27);
        MockPlayer ours = walker(helper, corridor, "bolt-own-crew", 1, EAST);
        MockPlayer theirs = walker(helper, corridor, "bolt-other-crew", 7, EAST);
        walker(helper, corridor, "bolt-no-charter", 12, EAST);
        MinecraftServer server = helper.getLevel().getServer();
        long[] startedAt = {-1};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            long now = corridor.level().getGameTime();
            if (startedAt[0] < 0) {
                CharterId mine = charter(helper, ours);
                charter(helper, theirs);
                withPvp(
                        server,
                        false,
                        () -> CaveIns.blasted(
                                corridor.level(), point(corridor.stand(7).add(0, 1, 0)), Optional.of(mine)));
                startedAt[0] = now;
                return;
            }
            if (now - startedAt[0] == CAVE_IN.warnTicks() + 3) {
                expect(helper, !corridor.standing(1), "a blast shakes the roof over a person of its own charter");
                expect(
                        helper,
                        corridor.standing(7),
                        "with PvP off a blast does not shake another charter's crew: that would be griefing");
                expect(helper, corridor.standing(12), "nor a person on no charter, with PvP off");
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void withPvpOnABlastShakesEveryonesRoof(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 29);
        MockPlayer ours = walker(helper, corridor, "bolt-pvp-own", 1, EAST);
        MockPlayer theirs = walker(helper, corridor, "bolt-pvp-other", 7, EAST);
        walker(helper, corridor, "bolt-pvp-none", 12, EAST);
        MinecraftServer server = helper.getLevel().getServer();
        long[] startedAt = {-1};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            long now = corridor.level().getGameTime();
            if (startedAt[0] < 0) {
                CharterId mine = charter(helper, ours);
                charter(helper, theirs);
                withPvp(
                        server,
                        true,
                        () -> CaveIns.blasted(
                                corridor.level(), point(corridor.stand(7).add(0, 1, 0)), Optional.of(mine)));
                startedAt[0] = now;
                return;
            }
            if (now - startedAt[0] == CAVE_IN.warnTicks() + 3) {
                expect(
                        helper,
                        !corridor.standing(1) && !corridor.standing(7) && !corridor.standing(12),
                        "where the server allows PvP a blast shakes every person on foot near it");
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void dynamiteFromAPodIsTheCharterOfItsPilotWhenPvpIsOff(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 31);
        MockPlayer pilot = walker(helper, corridor, "bolt-owner-pilot", 7, 90f);
        MockPlayer crew = walker(helper, corridor, "bolt-owner-crew", 1, EAST);
        MockPlayer stranger = walker(helper, corridor, "bolt-owner-stranger", 13, EAST);
        MinecraftServer server = helper.getLevel().getServer();
        long[] startedAt = {-1};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            ServerLevel level = corridor.level();
            long now = level.getGameTime();
            if (startedAt[0] < 0) {
                charter(helper, pilot);
                CharterId pilots = Charters.charterOfOrThrow(
                                server, pilot.player().getUUID())
                        .orElseThrow()
                        .id();
                expect(
                        helper,
                        Charters.apply(server, crew.player().getUUID(), pilots).isEmpty()
                                && Charters.approve(
                                                server,
                                                pilot.player().getUUID(),
                                                crew.player().getUUID())
                                        .isEmpty(),
                        "the crew member should join the pilot's charter");
                charter(helper, stranger);
                PodEntity pod = TestEntities.create(PodRegistry.POD, level);
                pod.setPos(corridor.stand(7));
                level.addFreshEntity(pod);
                pilot.player().startRiding(pod, true, false);
                pilot.player()
                        .getInventory()
                        .setItem(
                                pilot.player().getInventory().getSelectedSlot(),
                                new ItemStack(RepairRegistry.item(Consumable.DYNAMITE)));
                withPvp(server, false, () -> {
                    expect(
                            helper,
                            pilot.player()
                                    .gameMode
                                    .useItem(
                                            pilot.player(),
                                            level,
                                            pilot.player().getItemInHand(InteractionHand.MAIN_HAND),
                                            InteractionHand.MAIN_HAND)
                                    .consumesAction(),
                            "the pilot's dynamite should go off");
                });
                startedAt[0] = now;
                return;
            }
            if (now - startedAt[0] == CAVE_IN.warnTicks() + 3) {
                expect(helper, !corridor.standing(1), "dynamite shakes the roof over a person on the pilot's charter");
                expect(helper, corridor.standing(13), "with PvP off it does not shake another charter's crew");
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theDriverUsesBoltsInThePackAndTheOffhandAndNotThoseWorn(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 32);
        MockPlayer shooter = walker(helper, corridor, "bolt-slots", 1, EAST);
        ServerPlayer player = shooter.player();
        arm(player, 0);
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(RepairRegistry.item(Consumable.BOLT), 3));
        long[] stage = {0, 0};
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            if (stage[0] == 0) {
                expect(
                        helper,
                        !fire(player).consumesAction()
                                && player.getItemBySlot(EquipmentSlot.CHEST).getCount() == 3,
                        "bolts worn in an armor slot are not in the pack: the driver is empty, and they are kept");
                expect(
                        helper,
                        !setBolt(player, corridor.roof(1)).consumesAction()
                                && !corridor.level()
                                        .getBlockState(corridor.roof(1).below())
                                        .is(LayerBlocks.ROOF_BOLT),
                        "no roof bolt is set from bolts that are not in the pack");
                stage[0] = 1;
                stage[1] = helper.getTick();
                return;
            }
            if (stage[0] == 1) {
                if (helper.getTick() < stage[1] + MESSAGE_TICKS) {
                    return;
                }
                expect(
                        helper,
                        told(shooter, "message.deepcharter.repair.bolt_none"),
                        "an empty pack says so, got %s",
                        shooter.actionBarMessages());
                player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(RepairRegistry.item(Consumable.BOLT), 2));
                expect(
                        helper,
                        setBolt(player, corridor.roof(1)).consumesAction()
                                && corridor.level()
                                        .getBlockState(corridor.roof(1).below())
                                        .is(LayerBlocks.ROOF_BOLT),
                        "a bolt in the offhand is in the pack and sets a roof bolt");
                expect(
                        helper,
                        player.getOffhandItem().getCount() == 1
                                && player.getItemBySlot(EquipmentSlot.CHEST).getCount() == 3,
                        "the roof bolt took one bolt from the offhand and none of the worn ones: %s and %s",
                        player.getOffhandItem().getCount(),
                        player.getItemBySlot(EquipmentSlot.CHEST).getCount());
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPlayerWhoMayNotBuildHereSetsNoRoofBolt(GameTestHelper helper) {
        Corridor corridor = corridor(helper, LENGTH, 33);
        MockPlayer setter = walker(helper, corridor, "bolt-adventure", 5, EAST);
        setter.player().setGameMode(GameType.ADVENTURE);
        arm(setter.player(), 2);
        TestTicks.each(helper, () -> {
            if (!corridor.ready().isPresent() || helper.getTick() < SPAWN_PROTECTION_TICKS) {
                return;
            }
            BlockPos roof = corridor.roof(5);
            // Vanilla's ItemStack.useOn already turns an adventure player away before the driver is asked; the driver
            // asks again
            // (BoltDriverItem.mayHang), for a click that does not come through it.
            expect(
                    helper,
                    !setBolt(setter.player(), roof).consumesAction(),
                    "a player in adventure mode cannot place blocks and cannot set a roof bolt");
            expect(
                    helper,
                    !corridor.level().getBlockState(roof.below()).is(LayerBlocks.ROOF_BOLT)
                            && bolts(setter.player()) == 2,
                    "a refused bolt places nothing and spends nothing, the pack holds %d",
                    bolts(setter.player()));
            helper.succeed();
        });
    }
}
