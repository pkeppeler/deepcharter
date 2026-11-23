package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.material.PushReaction;

/**
 * Server GameTests for what vanilla's block movers do to a breach crust (#1110): explosions of every vanilla strength, pistons, a falling block, and the
 * mobs that carry or break blocks. A breach crust is a way through a floor that only the pod's drill, and a creative hand, may open (ADR 0059, ADR 0061),
 * so nothing vanilla may break or move one. Every test runs against the crust of every layer, derived from {@link CampaignBands}: an explosion that
 * breaks any crust skips the breach gating, not only the last floor's. Each test has its own X, and each layer its own Z.
 */
public class BreachCrustMoversTest {
    /** Half the side of the crust slab and of the room over it: both stay inside the one chunk the site is centred in. */
    private static final int HALF = 7;

    private static final int AIR_ROWS = 10;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 200;
    /** Ticks a blast has to settle once it is set off. */
    private static final int BLAST_TICKS = 10;
    /** Ticks a TNT's fuse burns before it goes off. */
    private static final int TNT_FUSE = 20;
    /** Ticks a sand block needs to fall the height of the room and settle. */
    private static final int FALL_TICKS = 60;

    private static final int TNT_SLOT = 0;
    private static final int CREEPER_SLOT = 1;
    private static final int CHARGED_CREEPER_SLOT = 2;
    private static final int END_CRYSTAL_SLOT = 3;
    private static final int BED_SLOT = 4;
    private static final int WITHER_SLOT = 5;
    private static final int PUSH_SLOT = 6;
    private static final int PULL_SLOT = 7;
    private static final int LIFT_SLOT = 8;
    private static final int FALL_SLOT = 9;

    /** The crust of one layer's floor, with the room over it. {@code floor} is the lowest crust row; the crust is {@code rows} thick. */
    private record Site(ServerLevel level, int layer, int x, int z, int floor, int rows) {
        BlockPos column() {
            return new BlockPos(x, floor, z);
        }

        /** The first row of air over the crust. */
        int roof() {
            return floor + rows;
        }

        int slabSize() {
            return (2 * HALF + 1) * (2 * HALF + 1) * rows;
        }

        int crustLeft() {
            int left = 0;
            for (BlockPos pos :
                    BlockPos.betweenClosed(x - HALF, floor, z - HALF, x + HALF, floor + rows - 1, z + HALF)) {
                if (level.getBlockState(pos).is(LayerBlocks.BREACH_CRUST)) {
                    left++;
                }
            }
            return left;
        }

        String describe() {
            return "layer " + layer + " (floor y " + floor + ")";
        }
    }

    private static List<Site> sites(GameTestHelper helper, int slot) {
        ServerLevel level = TestLayers.level(helper, 1);
        List<Site> sites = new ArrayList<>();
        for (int layer = 1; layer <= CampaignBands.lastLayer(); layer++) {
            int x = 16 * (1940 + 2 * slot) + 8;
            int z = 16 * (1940 + 2 * layer) + 8;
            sites.add(new Site(level, layer, x, z, TestLayers.band(level, layer).minY(), CampaignBands.crustRows()));
        }
        return sites;
    }

    /** The crust slab over rock, and a room of air over it. */
    private static void build(Site site) {
        RoomCarver.carve(
                site.level(),
                site.x() - HALF,
                site.x() + HALF,
                site.floor(),
                site.roof() - 1,
                site.z() - HALF,
                site.z() + HALF,
                LayerBlocks.BREACH_CRUST);
        RoomCarver.carve(
                site.level(),
                site.x() - HALF,
                site.x() + HALF,
                site.roof(),
                site.roof() + AIR_ROWS - 1,
                site.z() - HALF,
                site.z() + HALF,
                Blocks.AIR);
    }

    /**
     * Builds every layer's site once all their chunks tick, starts {@code hazard} in each on that same tick, and checks {@code verdict} (the problems it finds,
     * none when the crust held) {@code wait} ticks later. Every layer is checked before the test fails, so the failure names each layer that broke.
     */
    private static void run(
            GameTestHelper helper, int slot, int wait, Consumer<Site> hazard, Function<Site, List<String>> verdict) {
        List<Site> sites = sites(helper, slot);
        boolean[] ready = new boolean[sites.size()];
        long[] started = {-1};
        List<BlockPos> chunks = sites.stream().map(Site::column).toList();
        FarChunks.awaitEntityTicking(helper, sites.get(0).level(), chunks, index -> ready[index] = true);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    for (boolean chunk : ready) {
                        if (!chunk) {
                            throw helper.assertionException(Component.literal("a site's chunk is not ticking yet"));
                        }
                    }
                })
                .thenExecute(() -> {
                    for (Site site : sites) {
                        build(site);
                        hazard.accept(site);
                    }
                    started[0] = helper.getTick();
                })
                .thenWaitUntil(() -> {
                    if (helper.getTick() < started[0] + wait) {
                        throw helper.assertionException(Component.literal("the hazard has not settled yet"));
                    }
                })
                .thenExecute(() -> {
                    List<String> problems = new ArrayList<>();
                    for (Site site : sites) {
                        for (String problem : verdict.apply(site)) {
                            problems.add(site.describe() + ": " + problem);
                        }
                    }
                    if (!problems.isEmpty()) {
                        throw failure(
                                helper, "the crust did not hold:%n  %s", String.join("%n  ".formatted(), problems));
                    }
                })
                .thenSucceed();
    }

    /** Verdict for a blast: no crust block of the slab is gone. */
    private static List<String> slabWhole(Site site) {
        int left = site.crustLeft();
        return left == site.slabSize()
                ? List.of()
                : List.of((site.slabSize() - left) + " of " + site.slabSize() + " crust blocks gone");
    }

    private static void explodeAbove(Site site, float power, boolean fire, Level.ExplosionInteraction interaction) {
        site.level().explode(null, site.x() + 0.5, site.roof() + 0.5, site.z() + 0.5, power, fire, interaction);
    }

    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aTntBlastLeavesEveryCrustWhole(GameTestHelper helper) {
        run(
                helper,
                TNT_SLOT,
                TNT_FUSE + BLAST_TICKS,
                site -> {
                    PrimedTnt tnt = TestEntities.create(EntityTypes.TNT, site.level());
                    tnt.setPos(site.x() + 0.5, site.roof(), site.z() + 0.5);
                    tnt.setFuse(TNT_FUSE);
                    site.level().addFreshEntity(tnt);
                },
                BreachCrustMoversTest::slabWhole);
    }

    /** A creeper's blast is power 3. */
    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aCreeperBlastLeavesEveryCrustWhole(GameTestHelper helper) {
        run(
                helper,
                CREEPER_SLOT,
                BLAST_TICKS,
                site -> explodeAbove(site, 3f, false, Level.ExplosionInteraction.MOB),
                BreachCrustMoversTest::slabWhole);
    }

    /** A charged creeper's blast is power 6. */
    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aChargedCreeperBlastLeavesEveryCrustWhole(GameTestHelper helper) {
        run(
                helper,
                CHARGED_CREEPER_SLOT,
                BLAST_TICKS,
                site -> explodeAbove(site, 6f, false, Level.ExplosionInteraction.MOB),
                BreachCrustMoversTest::slabWhole);
    }

    /** A struck end crystal explodes at power 6 as a block explosion. */
    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void anEndCrystalBlastLeavesEveryCrustWhole(GameTestHelper helper) {
        run(
                helper,
                END_CRYSTAL_SLOT,
                BLAST_TICKS,
                site -> {
                    EndCrystal crystal = TestEntities.create(EntityTypes.END_CRYSTAL, site.level());
                    crystal.setPos(site.x() + 0.5, site.roof(), site.z() + 0.5);
                    site.level().addFreshEntity(crystal);
                    crystal.hurtServer(
                            site.level(), site.level().damageSources().generic(), 1f);
                },
                BreachCrustMoversTest::slabWhole);
    }

    /** A bed slept in outside the overworld, and a respawn anchor outside the nether, both explode at power 5 with fire. */
    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aBedOrRespawnAnchorBlastLeavesEveryCrustWhole(GameTestHelper helper) {
        run(
                helper,
                BED_SLOT,
                BLAST_TICKS,
                site -> explodeAbove(site, 5f, true, Level.ExplosionInteraction.BLOCK),
                BreachCrustMoversTest::slabWhole);
    }

    /** A wither's spawn blast is power 7, the strongest vanilla explosion. */
    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aWitherSpawnBlastLeavesEveryCrustWhole(GameTestHelper helper) {
        run(
                helper,
                WITHER_SLOT,
                BLAST_TICKS,
                site -> explodeAbove(site, 7f, false, Level.ExplosionInteraction.MOB),
                BreachCrustMoversTest::slabWhole);
    }

    /** A piston beside a free crust block pushes it. */
    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aPistonCannotPushOneCrustBlock(GameTestHelper helper) {
        run(
                helper,
                PUSH_SLOT,
                BLAST_TICKS,
                site -> {
                    BlockPos crust = hover(site);
                    site.level().setBlock(crust, LayerBlocks.BREACH_CRUST.defaultBlockState(), Block.UPDATE_ALL);
                    BlockPos base = crust.west();
                    site.level()
                            .setBlock(
                                    base,
                                    Blocks.PISTON.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.EAST),
                                    Block.UPDATE_ALL);
                    site.level().setBlock(base.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                },
                site -> stillThere(site, hover(site)));
    }

    /** A sticky piston retracting pulls the block in front of its head back with it. */
    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aStickyPistonCannotPullOneCrustBlock(GameTestHelper helper) {
        run(
                helper,
                PULL_SLOT,
                BLAST_TICKS,
                site -> {
                    BlockPos crust = hover(site);
                    BlockPos head = crust.west();
                    BlockPos base = head.west();
                    site.level()
                            .setBlock(
                                    head,
                                    Blocks.PISTON_HEAD
                                            .defaultBlockState()
                                            .setValue(DirectionalBlock.FACING, Direction.EAST)
                                            .setValue(PistonHeadBlock.TYPE, PistonType.STICKY),
                                    Block.UPDATE_CLIENTS);
                    site.level().setBlock(crust, LayerBlocks.BREACH_CRUST.defaultBlockState(), Block.UPDATE_ALL);
                    // Extended and unpowered: the base retracts as it is placed.
                    site.level()
                            .setBlock(
                                    base,
                                    Blocks.STICKY_PISTON
                                            .defaultBlockState()
                                            .setValue(DirectionalBlock.FACING, Direction.EAST)
                                            .setValue(PistonBaseBlock.EXTENDED, true),
                                    Block.UPDATE_ALL);
                },
                site -> stillThere(site, hover(site)));
    }

    /** A piston in a pit under the floor pushes the crust up into the room: the way up through a crust a player already holds the other side of. */
    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aPistonUnderTheFloorCannotLiftTheCrust(GameTestHelper helper) {
        run(
                helper,
                LIFT_SLOT,
                BLAST_TICKS,
                site -> {
                    RoomCarver.carve(
                            site.level(),
                            site.x() - 1,
                            site.x() + 1,
                            site.floor() - 3,
                            site.floor() - 1,
                            site.z() - 1,
                            site.z() + 1,
                            Blocks.AIR);
                    BlockPos base = new BlockPos(site.x(), site.floor() - 1, site.z());
                    site.level()
                            .setBlock(
                                    base,
                                    Blocks.PISTON.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.UP),
                                    Block.UPDATE_ALL);
                    site.level().setBlock(base.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                },
                site -> {
                    List<String> problems = new ArrayList<>(slabWhole(site));
                    if (!site.level()
                            .getBlockState(site.column().above(site.rows()))
                            .isAir()) {
                        problems.add("the crust column was lifted into the room");
                    }
                    return problems;
                });
    }

    /** A sand block dropped onto the crust lands on it and leaves it alone. */
    @GameTest(environment = CampaignEnvironments.D, maxTicks = MAX_TICKS)
    public void aFallingBlockLandingOnTheCrustLeavesItWhole(GameTestHelper helper) {
        run(
                helper,
                FALL_SLOT,
                FALL_TICKS,
                site -> site.level()
                        .setBlock(
                                new BlockPos(site.x(), site.roof() + AIR_ROWS - 2, site.z()),
                                Blocks.SAND.defaultBlockState(),
                                Block.UPDATE_ALL),
                site -> {
                    List<String> problems = new ArrayList<>(slabWhole(site));
                    if (!site.level()
                            .getBlockState(site.column().above(site.rows()))
                            .is(Blocks.SAND)) {
                        problems.add("the sand did not land on the crust, "
                                + site.level().getBlockState(site.column().above(site.rows())));
                    }
                    return problems;
                });
    }

    /** The mobs that break or carry blocks decide by block tag or by a static check, not by the world: no chunk is needed. */
    @GameTest
    public void noMobThatBreaksOrCarriesBlocksTakesTheCrust(GameTestHelper helper) {
        BlockState crust = LayerBlocks.BREACH_CRUST.defaultBlockState();
        if (WitherBoss.canDestroy(crust)) {
            throw failure(helper, "a wither breaks the crust as it flies");
        }
        if (!crust.is(BlockTags.WITHER_IMMUNE)) {
            throw failure(helper, "the crust is not in minecraft:wither_immune");
        }
        if (!crust.is(BlockTags.DRAGON_IMMUNE)) {
            throw failure(
                    helper, "the crust is not in minecraft:dragon_immune: the ender dragon breaks it as it flies");
        }
        if (crust.is(BlockTags.ENDERMAN_HOLDABLE)) {
            throw failure(helper, "an enderman can pick up the crust");
        }
        helper.succeed();
    }

    /** Whether the blast, the push and the fall can touch the crust at all is its own properties': a blast of any power meets this resistance. */
    @GameTest
    public void theCrustHasTheResistanceOfBedrockAndNoPushReaction(GameTestHelper helper) {
        BlockState crust = LayerBlocks.BREACH_CRUST.defaultBlockState();
        if (LayerBlocks.BREACH_CRUST.getExplosionResistance() < Blocks.BEDROCK.getExplosionResistance()) {
            throw failure(
                    helper,
                    "the crust must shrug off any explosion, its resistance is %s",
                    LayerBlocks.BREACH_CRUST.getExplosionResistance());
        }
        if (crust.getPistonPushReaction() != PushReaction.IMMOVEABLE) {
            throw failure(
                    helper, "a piston can move the crust: its push reaction is %s", crust.getPistonPushReaction());
        }
        helper.succeed();
    }

    /** The crust block that hangs free in the room over a site, with air on every side. */
    private static BlockPos hover(Site site) {
        return new BlockPos(site.x(), site.roof() + 4, site.z());
    }

    private static List<String> stillThere(Site site, BlockPos crust) {
        List<String> problems = new ArrayList<>();
        if (!site.level().getBlockState(crust).is(LayerBlocks.BREACH_CRUST)) {
            problems.add("the crust block was moved off " + crust.toShortString());
        }
        return problems;
    }
}
