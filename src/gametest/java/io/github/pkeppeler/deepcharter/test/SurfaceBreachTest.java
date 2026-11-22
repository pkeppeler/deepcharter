package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for the surface: the breach in place between the surface band (layer 0) and layer 1 of the campaign level, the open
 * overworld floor, the structures that are gone, and portals that cannot be lit. Each crossing test uses
 * its own X/Z so the columns it carves do not touch another test's.
 */
public class SurfaceBreachTest {
    /** Ticks a fall, once the chunk ticks, needs to cross a breach and be checked. */
    private static final int CROSSING_TICKS = 200;
    /** Crossings from layer 0 into layer 1 seen per entity UUID, so a test can tell the event fired for its player. */
    private static final Map<UUID, Integer> FROM_SURFACE = new ConcurrentHashMap<>();
    /** Every crossing seen per entity UUID. */
    private static final Map<UUID, Integer> CROSSINGS = new ConcurrentHashMap<>();

    static {
        BreachEvents.CROSSED.register((entity, from, to, fromLayer, toLayer) -> {
            CROSSINGS.merge(entity.getUUID(), 1, Integer::sum);
            if (fromLayer == LayerChain.SURFACE && toLayer == 1) {
                FROM_SURFACE.merge(entity.getUUID(), 1, Integer::sum);
            }
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void fallingOutOfTheSurfaceFloorArrivesInLayerOneInPlace(GameTestHelper helper) {
        double x = 2500.5;
        double z = 2500.5;
        ServerLevel level = TestLayers.level(helper, 1);
        openShaft(level, x, z);
        MockPlayer mock = MockPlayers.join(helper, "surface-falls");
        mock.teleportTo(
                level, new Vec3(x, TestLayers.band(level, TestLayers.SURFACE).minY() + 6, z), 0, 0);
        fallWhileIn(helper, mock, level, TestLayers.SURFACE);
        helper.succeedWhen(() -> {
            ServerPlayer player = mock.player();
            expectIn(helper, player, 1);
            if (player.level() != level) {
                throw failure(
                        helper,
                        "the crossing moved the player to %s",
                        player.level().dimension());
            }
            if (FROM_SURFACE.getOrDefault(player.getUUID(), 0) != 1) {
                throw failure(
                        helper, "expected one CROSSED event from layer 0, saw %s", FROM_SURFACE.get(player.getUUID()));
            }
            if (Math.abs(player.getX() - x) > 0.5 || Math.abs(player.getZ() - z) > 0.5) {
                throw failure(helper, "arrived at x=%s z=%s, expected %s %s", player.getX(), player.getZ(), x, z);
            }
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void aMinecartRidesDownFromTheSurfaceInPlaceStillRiding(GameTestHelper helper) {
        double x = 2600.5;
        double z = 2600.5;
        ServerLevel level = TestLayers.level(helper, 1);
        openShaft(level, x, z);
        MockPlayer mock = MockPlayers.join(helper, "surface-rides");
        double y = TestLayers.band(level, TestLayers.SURFACE).minY() + 6;
        mock.teleportTo(level, new Vec3(x, y, z), 0, 0);
        boolean[] ticking = {false};
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(x, y, z), () -> {
            Minecart cart = TestEntities.create(EntityTypes.MINECART, level);
            cart.setPos(x, y, z);
            level.addFreshEntity(cart);
            if (!mock.player().startRiding(cart, true, false)) {
                throw failure(helper, "the mock could not board the minecart");
            }
            ticking[0] = true;
        });
        TestTicks.each(helper, () -> {
            ServerPlayer player = mock.player();
            if (ticking[0] && TestLayers.isIn(player, TestLayers.SURFACE)) {
                Entity root = player.getRootVehicle();
                root.setPos(root.getX(), root.getY() - 1, root.getZ());
            }
        });
        helper.succeedWhen(() -> {
            ServerPlayer player = mock.player();
            expectIn(helper, player, 1);
            if (!(player.getVehicle() instanceof Minecart)
                    || player.getVehicle().level() != player.level()) {
                throw failure(helper, "the player is riding %s after crossing, not a minecart", player.getVehicle());
            }
        });
    }

    /** A crossing up is seen from the layer last seen, so the player is first put in layer 1 and then raised over the surface floor. */
    @GameTest(maxTicks = 200)
    public void risingAboveTheSurfaceFloorReturnsToTheSurfaceInPlace(GameTestHelper helper) {
        double x = 2700.5;
        double z = 2700.5;
        ServerLevel level = TestLayers.level(helper, 1);
        MockPlayer mock = MockPlayers.join(helper, "surface-rises");
        mock.player().setNoGravity(true);
        mock.teleportTo(level, TestLayers.vec(1, x, 100, z), 0, 0);
        int floor = TestLayers.band(level, TestLayers.SURFACE).minY();
        helper.runAfterDelay(
                20,
                () -> mock.teleportTo(level, new Vec3(x, floor + LayerTuning.DEFAULT.crustThickness() + 1, z), 0, 0));
        helper.succeedWhen(() -> {
            ServerPlayer player = mock.player();
            if (helper.getTick() < 21) {
                throw failure(helper, "waiting for the player to be raised");
            }
            expectIn(helper, player, TestLayers.SURFACE);
            if (player.level() != level || CROSSINGS.getOrDefault(player.getUUID(), 0) != 1) {
                throw failure(
                        helper,
                        "expected one crossing up in %s, saw %s in %s",
                        level.dimension(),
                        CROSSINGS.get(player.getUUID()),
                        player.level().dimension());
            }
        });
    }

    @GameTest(maxTicks = 60)
    public void theSurfaceHasNoCrossingAtTheSkyLimit(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 1);
        MockPlayer mock = MockPlayers.join(helper, "surface-sky");
        mock.player().setNoGravity(true);
        mock.teleportTo(
                level,
                new Vec3(2800.5, TestLayers.band(level, TestLayers.SURFACE).maxY() - 5, 2800.5),
                0,
                0);
        helper.runAfterDelay(
                10,
                () -> mock.teleportTo(
                        level,
                        new Vec3(
                                2800.5,
                                TestLayers.band(level, TestLayers.SURFACE).maxY() + 5,
                                2800.5),
                        0,
                        0));
        helper.runAfterDelay(40, () -> {
            if (mock.player().level() != level
                    || CROSSINGS.containsKey(mock.player().getUUID())) {
                throw failure(
                        helper,
                        "the player at the sky limit crossed (%s) or left the level (%s)",
                        CROSSINGS.get(mock.player().getUUID()),
                        mock.player().level().dimension());
            }
            helper.succeed();
        });
    }

    /** The overworld's rule is ours (ADR 0036): rock to the bottom of the world, so the floor is open to the breach (ADR 0011). */
    @GameTest
    public void theOverworldFloorHasNoBedrock(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        ResourceKey<NoiseGeneratorSettings> surface = ResourceKey.create(
                Registries.NOISE_SETTINGS, Identifier.fromNamespaceAndPath("deepcharter", "surface"));
        NoiseGeneratorSettings settings =
                registries.lookupOrThrow(Registries.NOISE_SETTINGS).getValueOrThrow(surface);
        JsonElement rule = MaterialRule.DIRECT_CODEC
                .encodeStart(
                        RegistryOps.create(JsonOps.INSTANCE, registries),
                        settings.materialRule().value())
                .getOrThrow(message -> failure(helper, "could not encode the surface material rule: %s", message));
        if (rule.toString().contains("bedrock")) {
            throw failure(helper, "the surface material rule places bedrock: %s", rule);
        }
        helper.succeed();
    }

    @GameTest
    public void villagesOutpostsAndStrongholdsAreGone(GameTestHelper helper) {
        var sets = helper.getLevel().registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
        for (String name : List.of("villages", "pillager_outposts", "strongholds")) {
            ResourceKey<StructureSet> key =
                    ResourceKey.create(Registries.STRUCTURE_SET, Identifier.withDefaultNamespace(name));
            StructureSet set = sets.getValueOrThrow(key);
            if (!set.structures().isEmpty()) {
                throw failure(helper, "structure set %s still holds %s", name, set.structures());
            }
        }
        helper.succeed();
    }

    @GameTest
    public void aPortalCannotBeLit(GameTestHelper helper) {
        ServerLevel surface = surface(helper);
        BlockPos origin = TestLayers.pos(TestLayers.SURFACE, 3000, 40, 3000);
        // A 4 wide, 5 tall obsidian frame in the x axis, with a 2 by 3 opening.
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                boolean edge = dx == 0 || dx == 3 || dy == 0 || dy == 4;
                // room-carver: a portal frame on the surface, not layer rock
                surface.setBlock(
                        origin.offset(dx, dy, 0),
                        edge ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                        3);
            }
        }
        BlockPos inside = origin.offset(1, 1, 0);
        // Control: the frame is valid, so only the missing portal dimension can stop the portal.
        if (PortalShape.findEmptyPortalShape(surface, inside, Direction.Axis.X).isEmpty()) {
            throw failure(helper, "the obsidian frame is not a valid portal frame");
        }
        surface.setBlock(inside, Blocks.FIRE.defaultBlockState(), 3);
        for (int dx = 1; dx <= 2; dx++) {
            for (int dy = 1; dy <= 3; dy++) {
                if (surface.getBlockState(origin.offset(dx, dy, 0)).is(Blocks.NETHER_PORTAL)) {
                    throw failure(helper, "a portal lit at %s", origin.offset(dx, dy, 0));
                }
            }
        }
        helper.succeed();
    }

    /** A mock has no client gravity, so drop it a block a tick while in {@code layer}, once its chunk ticks. */
    private static void fallWhileIn(GameTestHelper helper, MockPlayer mock, ServerLevel level, int layer) {
        boolean[] ticking = {false};
        FarChunks.awaitEntityTicking(helper, level, mock.player().blockPosition(), () -> ticking[0] = true);
        TestTicks.each(helper, () -> {
            ServerPlayer player = mock.player();
            if (ticking[0] && TestLayers.isIn(player, layer)) {
                player.setPos(player.getX(), player.getY() - 1, player.getZ());
            }
        });
    }

    /** Clear the surface floor of {@code level} at one column, over a pocket in the top of layer 1, so an entity above it falls straight out of the surface. */
    private static void openShaft(ServerLevel level, double x, double z) {
        BlockPos column = BlockPos.containing(x, 0, z);
        int floor = TestLayers.band(level, TestLayers.SURFACE).minY();
        RoomCarver.carvePocketBelow(
                level, TestLayers.SURFACE, column.getX(), column.getX(), column.getZ(), column.getZ());
        RoomCarver.carve(
                level, column.atY(floor), column.atY(floor + 10), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void expectIn(GameTestHelper helper, Entity entity, int layer) {
        if (!TestLayers.isIn(entity, layer)) {
            throw failure(
                    helper,
                    "%s is at y=%s in %s, expected layer %d",
                    entity.getName().getString(),
                    entity.getY(),
                    entity.level().dimension(),
                    layer);
        }
    }

    private static ServerLevel surface(GameTestHelper helper) {
        return helper.getLevel().getServer().overworld();
    }
}
