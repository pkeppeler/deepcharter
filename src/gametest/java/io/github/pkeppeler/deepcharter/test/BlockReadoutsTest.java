package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.layer.BlockReadouts;
import io.github.pkeppeler.deepcharter.layer.BlockReadouts.Readout;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.OreBlocks;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.texture.TextureProperties;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for the block readout's words (#859): which name and hint a block yields, that only the block hit is read, that a disguised
 * block reads as its disguise, and that a feature can add a hint without touching the others. The readout is plain Java over a state and a
 * place, so the depth rule of rock is checked in layer levels without loading a chunk; {@code BlockReadoutClientTest} checks the drawing.
 */
public class BlockReadoutsTest {
    private static final BlockReadouts SHARED = BlockReadouts.SHARED;
    private static final String POD_DRILL_ONLY = "hint.deepcharter.pod_drill_only";

    private static String key(GameTestHelper helper, Component component) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            return translatable.getKey();
        }
        throw helper.assertionException("expected a translatable component, got %s", component);
    }

    private static Readout readout(GameTestHelper helper, BlockState state, Level level) {
        return SHARED.readout(state, level, BlockPos.ZERO, helper.makeMockPlayer(GameType.SURVIVAL))
                .orElseThrow(() -> helper.assertionException("%s has no readout", state));
    }

    /** The readout of {@code state} at the lowest block of {@code layer}, in the level that holds it: in the campaign level, a position decides the layer. */
    private static Readout readoutIn(GameTestHelper helper, BlockState state, int layer) {
        Level level = TestLayers.level(helper, layer);
        BlockPos pos = new BlockPos(0, TestLayers.band(level, layer).minY(), 0);
        return SHARED.readout(state, level, pos, helper.makeMockPlayer(GameType.SURVIVAL))
                .orElseThrow(() -> helper.assertionException("%s has no readout", state));
    }

    private static void expectWords(
            GameTestHelper helper, Readout readout, String nameKey, Optional<String> hintKey, String what) {
        String name = key(helper, readout.name());
        Optional<String> hint = readout.hint().map(component -> key(helper, component));
        if (!name.equals(nameKey) || !hint.equals(hintKey)) {
            throw helper.assertionException(
                    "%s: expected %s with hint %s, got %s with hint %s", what, nameKey, hintKey, name, hint);
        }
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    @GameTest
    public void aPlainBlockHasANameAndNoHint(GameTestHelper helper) {
        Readout readout = readout(helper, Blocks.STONE.defaultBlockState(), helper.getLevel());
        expectWords(helper, readout, "block.minecraft.stone", Optional.empty(), "stone on the surface");
        helper.succeed();
    }

    @GameTest
    public void airFluidMissAndEntityReadNothing(GameTestHelper helper) {
        Level level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        Player viewer = helper.makeMockPlayer(GameType.SURVIVAL);
        for (BlockState state : new BlockState[] {Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState()}) {
            if (SHARED.readout(state, level, pos, viewer).isPresent()) {
                throw helper.assertionException("%s should read nothing", state);
            }
        }
        if (SHARED.resolve(level, BlockHitResult.miss(Vec3.ZERO, Direction.UP, pos), viewer)
                .isPresent()) {
            throw helper.assertionException("a miss should read nothing");
        }
        EntityHitResult entity = new EntityHitResult(TestEntities.create(PodRegistry.POD, level));
        if (SHARED.resolve(level, entity, viewer).isPresent()) {
            throw helper.assertionException("an entity should read nothing");
        }
        helper.succeed();
    }

    @GameTest
    public void onlyTheBlockHitIsRead(GameTestHelper helper) {
        BlockPos front = new BlockPos(1, 1, 1);
        helper.setBlock(front, Blocks.STONE.defaultBlockState());
        helper.setBlock(front.east(), OreBlocks.of(OreType.IRONIUM).defaultBlockState());
        helper.setBlock(front.above(), Blocks.AIR.defaultBlockState());
        Readout readout = SHARED.resolve(
                        helper.getLevel(), hit(helper.absolutePos(front)), helper.makeMockPlayer(GameType.SURVIVAL))
                .orElseThrow(() -> helper.assertionException("the stone had no readout"));
        expectWords(helper, readout, "block.minecraft.stone", Optional.empty(), "the block hit, with ore behind it");
        helper.succeed();
    }

    @GameTest
    public void aTerminalSaysHowToUseIt(GameTestHelper helper) {
        for (Block block : new Block[] {ContractTerminal.TYPE.block(), TerminalTypes.FUEL_PUMP.block()}) {
            Readout readout = readout(helper, block.defaultBlockState(), helper.getLevel());
            expectWords(
                    helper,
                    readout,
                    block.getDescriptionId(),
                    Optional.of("hint.deepcharter.right_click_use"),
                    "a terminal");
        }
        helper.succeed();
    }

    @GameTest
    public void aNoteSaysHowToReadIt(GameTestHelper helper) {
        Readout readout = readout(helper, NoteBlock.stateOf(1), helper.getLevel());
        expectWords(
                helper, readout, "block.deepcharter.note", Optional.of("hint.deepcharter.right_click_read"), "a note");
        helper.succeed();
    }

    @GameTest
    public void breachCrustIsPodDrillOnly(GameTestHelper helper) {
        Readout readout = readout(helper, LayerBlocks.BREACH_CRUST.defaultBlockState(), helper.getLevel());
        expectWords(helper, readout, "block.deepcharter.breach_crust", Optional.of(POD_DRILL_ONLY), "breach crust");
        helper.succeed();
    }

    @GameTest
    public void companyRockSaysWhatBreaksIt(GameTestHelper helper) {
        Readout readout = readout(helper, HazardBlocks.COMPANY_ROCK.defaultBlockState(), helper.getLevel());
        expectWords(
                helper,
                readout,
                "block.deepcharter.company_rock",
                Optional.of("hint.deepcharter.company_rock"),
                "company rock");
        helper.succeed();
    }

    @GameTest
    public void deepRockIsPodDrillOnlyOnlyBelowLayerOne(GameTestHelper helper) {
        BlockState shale = LayerBlocks.GREEN_SHALE.defaultBlockState();
        for (int layer : new int[] {TestLayers.SURFACE, 1}) {
            Readout readout = readoutIn(helper, shale, layer);
            expectWords(helper, readout, "block.deepcharter.green_shale", Optional.empty(), "rock in layer " + layer);
        }
        Readout deep = readoutIn(helper, shale, 2);
        expectWords(helper, deep, "block.deepcharter.green_shale", Optional.of(POD_DRILL_ONLY), "rock in layer 2");
        helper.succeed();
    }

    @GameTest
    public void aDisguisedBlockReadsAsItsDisguiseHintIncluded(GameTestHelper helper) {
        BlockState pocket = HazardBlocks.GAS_POCKET.defaultBlockState();
        Readout shallow = readoutIn(helper, pocket, 1);
        expectWords(helper, shallow, "block.minecraft.stone", Optional.empty(), "a gas pocket in layer 1");
        Readout deep = readoutIn(helper, pocket, 2);
        expectWords(helper, deep, "block.minecraft.stone", Optional.of(POD_DRILL_ONLY), "a gas pocket in layer 2");
        Readout stone = readoutIn(helper, Blocks.STONE.defaultBlockState(), 2);
        if (!stone.equals(deep)) {
            throw helper.assertionException("a gas pocket read %s, stone %s: they must read alike", deep, stone);
        }
        helper.succeed();
    }

    @GameTest
    public void anOreReadsAsTheOreItLooksLike(GameTestHelper helper) {
        for (OreType type : OreType.values()) {
            Block ore = OreBlocks.of(type);
            Readout readout = readout(helper, ore.defaultBlockState(), helper.getLevel());
            expectWords(helper, readout, ore.getDescriptionId(), Optional.empty(), type + " on the surface");
        }
        helper.succeed();
    }

    @GameTest
    public void aFeatureAddsAHintFromStateAndPlaceWithoutTouchingTheOthers(GameTestHelper helper) {
        BlockReadouts own = new BlockReadouts();
        Player viewer = helper.makeMockPlayer(GameType.SURVIVAL);
        own.addHint((state, level, pos, who) -> state.is(Blocks.DIRT) && pos.getY() < 0
                ? Optional.of(Component.translatable("hint.test.deep_dirt"))
                : Optional.empty());
        own.addHint((state, level, pos, who) ->
                state.is(Blocks.DIRT) ? Optional.of(Component.translatable("hint.test.any_dirt")) : Optional.empty());
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        Level level = helper.getLevel();
        String deep = own.readout(dirt, level, new BlockPos(0, -5, 0), viewer)
                .flatMap(Readout::hint)
                .map(component -> key(helper, component))
                .orElse("none");
        String shallow = own.readout(dirt, level, new BlockPos(0, 5, 0), viewer)
                .flatMap(Readout::hint)
                .map(component -> key(helper, component))
                .orElse("none");
        if (!deep.equals("hint.test.deep_dirt") || !shallow.equals("hint.test.any_dirt")) {
            throw helper.assertionException(
                    "the first answering provider should win by place: deep %s, shallow %s", deep, shallow);
        }
        if (own.readout(Blocks.STONE.defaultBlockState(), level, BlockPos.ZERO, viewer)
                .orElseThrow()
                .hint()
                .isPresent()) {
            throw helper.assertionException("a provider for dirt should leave stone without a hint");
        }
        helper.succeed();
    }

    @GameTest
    public void aBlockCannotBeDisguisedTwice(GameTestHelper helper) {
        BlockReadouts own = new BlockReadouts();
        own.disguise(Blocks.DIRT, Blocks.STONE);
        try {
            own.disguise(Blocks.DIRT, Blocks.GRANITE);
        } catch (IllegalStateException expected) {
            helper.succeed();
            return;
        }
        throw helper.assertionException("a second disguise of one block should be refused");
    }

    @GameTest
    public void aCreativePlayerReadsNoPodDrillHint(GameTestHelper helper) {
        Player creative = helper.makeMockPlayer(GameType.CREATIVE);
        for (BlockState state : new BlockState[] {
            LayerBlocks.BREACH_CRUST.defaultBlockState(), LayerBlocks.GREEN_SHALE.defaultBlockState()
        }) {
            Level deep = TestLayers.level(helper, 2);
            BlockPos pos = new BlockPos(0, TestLayers.band(deep, 2).minY(), 0);
            Readout readout = SHARED.readout(state, deep, pos, creative)
                    .orElseThrow(() -> helper.assertionException("%s has no readout", state));
            if (readout.hint().isPresent()) {
                throw helper.assertionException("a creative player read a hint on %s: %s", state, readout.hint());
            }
        }
        helper.succeed();
    }

    @GameTest
    public void anOfflineTerminalStillSaysRightClickBecauseItOpensToTakeParts(GameTestHelper helper) {
        BlockState offline = TerminalTypes.FUEL_PUMP.block().defaultBlockState();
        if (offline.getValue(TextureProperties.ACTIVE)) {
            throw helper.assertionException("the fuel pump should start offline");
        }
        Readout readout = readout(helper, offline, helper.getLevel());
        expectWords(
                helper,
                readout,
                TerminalTypes.FUEL_PUMP.block().getDescriptionId(),
                Optional.of("hint.deepcharter.right_click_use"),
                "an offline terminal");
        helper.succeed();
    }
}
