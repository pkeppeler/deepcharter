package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.Drifter;
import io.github.pkeppeler.deepcharter.creature.DrifterVariant;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "drifter-variants" (#1204): the three forms of a Drifter as turntable stills, in a closed dark hall
 * of layer 1 filled with the test pack's hollows biome, which carries the Spore Fields' gas haze (fog from 2 to 20
 * blocks, dust motes, no sky light). Each form is shot close under a dim lamp over it, with its animation clock held at
 * its first frame: from four angles (front, three quarters, side, below); once with the HUD hidden; once mid-loop, to
 * show its sway; and in a full turn of {@value #TURNTABLE_FRAMES} frames. Then all three are shot together at 12 to 16
 * blocks in the haze, with no lamp, so that only their glow shows. The hall is sealed rock, so the sky is never in
 * view; the camera is in a layer, where {@code LayerSky} hides the sun and moon anyway.
 */
public class DrifterVariantsScenario extends EvidenceScenario {
    private static final int X = 6400;
    private static final int Z = 6400;
    private static final int FLOOR_Y = LAYER_ROOM_Y;
    private static final int HALF_LENGTH = 24;
    private static final int HALF_WIDTH = 12;
    private static final int HEIGHT = 16;
    /** Blocks over the floor that a drifter hangs by the hall's middle: its feet. */
    private static final double HANG_BLOCKS = 6.0;

    private static final double EYE_BLOCKS = 1.62;
    private static final int LAMP_LEVEL = 9;
    /** Frames of a turntable: one turn of the camera round a form, in even steps. */
    private static final int TURNTABLE_FRAMES = 24;
    /** The animation clock, in ticks, of the one still of each form taken mid-loop, so that its sway shows. */
    private static final float SWAY_AGE = 26f;

    private static final double LAMP_OVER_BLOCKS = 3.5;
    private static final Identifier HOLLOWS = Identifier.fromNamespaceAndPath("deepcharter-test", "hollows");

    /**
     * How far the camera is from a form for the close stills, and the height over its feet of the point the camera
     * looks at.
     */
    private record Frame(double distance, double focusUp) {}

    private static Frame frame(DrifterVariant variant) {
        return switch (variant) {
            case JELLYFISH -> new Frame(6.5, 0.6);
            case SAC -> new Frame(4.8, 1.3);
            case CHAIN -> new Frame(5.8, 1.7);
        };
    }

    /**
     * The turns of the camera round a form, in degrees from its front, and the way they are named; below is looking up
     * from beneath.
     */
    private record Angle(String name, double yaw, double pitch) {}

    private static final List<Angle> ANGLES = List.of(
            new Angle("front", 0, 5), new Angle("34", 40, 18), new Angle("side", 90, 4), new Angle("below", 35, -52));

    @Override
    protected String name() {
        return "drifter-variants";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        EvidencePins.drifterAnimationAge = 0f;
        EvidencePins.handsHidden = true;
        EvidencePins.itemNameHidden = true;
        EvidencePins.vignetteHidden = true;
        EvidencePins.lightFlickerOff = true;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40); // tick-wait: the world settles before the hall is built
            singleplayer.getServer().runOnServer(server -> {
                server.getGameRules().set(GameRules.SPAWN_MONSTERS, false, server);
                ServerLevel level = TestLayers.level(server, 1);
                build(level);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.setNoGravity(true);
                player.teleportTo(level, X + 0.5, FLOOR_Y + 1, Z + 0.5, Set.of(), 0f, 0f, true);
            });
            ClientWait.chunksAround(context, X, Z, HALF_LENGTH + 2);
            ClientWait.until(
                    context,
                    "the player in layer 1 with the hall meshed",
                    client -> client.player != null
                            && TestLayers.clientIsIn(client, 1)
                            && client.levelRenderer.hasRenderedAllSections());
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            Vec3 middle = new Vec3(X + 0.5, FLOOR_Y + HANG_BLOCKS, Z + 0.5);
            for (DrifterVariant variant : DrifterVariant.values()) {
                close(context, singleplayer, variant, middle);
            }
            range(context, singleplayer);
        } finally {
            EvidencePins.reset();
        }
    }

    /** One form alone in the hall's middle, lit from over it, shot from each angle, and once with the HUD hidden. */
    private void close(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, DrifterVariant variant, Vec3 feet) {
        Frame frame = frame(variant);
        Vec3 focus = feet.add(0, frame.focusUp(), 0);
        UUID id = place(singleplayer, variant, feet);
        lamp(singleplayer, BlockPos.containing(feet.add(0, LAMP_OVER_BLOCKS, 0)), true);
        for (Angle angle : ANGLES) {
            look(context, singleplayer, around(focus, frame.distance(), angle), focus);
            screenshot(context, "drifter-variants-" + variant.id() + "-" + angle.name());
            frame(context);
            if (angle.name().equals("34")) {
                ClientChecks.withHudHidden(
                        context, () -> screenshot(context, "drifter-variants-" + variant.id() + "-nohud"));
            }
        }
        Angle swayed = ANGLES.get(1);
        EvidencePins.drifterAnimationAge = SWAY_AGE;
        look(context, singleplayer, around(focus, frame.distance(), swayed), focus);
        screenshot(context, "drifter-variants-" + variant.id() + "-sway");
        EvidencePins.drifterAnimationAge = 0f;
        for (int turn = 0; turn < TURNTABLE_FRAMES; turn++) {
            Angle step = new Angle("turn", 360.0 * turn / TURNTABLE_FRAMES, 8);
            look(context, singleplayer, around(focus, frame.distance(), step), focus);
            screenshot(context, "drifter-variants-%s-turn-%02d".formatted(variant.id(), turn));
            frame(context);
        }
        lamp(singleplayer, BlockPos.containing(feet.add(0, LAMP_OVER_BLOCKS, 0)), false);
        remove(singleplayer, id);
    }

    /** All three in a row 12, 14 and 16 blocks from the camera at the hall's west end, in the haze, under no lamp. */
    private void range(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        Vec3 eye = new Vec3(X - HALF_LENGTH + 3.0, FLOOR_Y + HANG_BLOCKS + 1.5, Z + 0.5);
        List<UUID> made = List.of(
                place(singleplayer, DrifterVariant.JELLYFISH, eye.add(12, -1.5 - 0.6, -5)),
                place(singleplayer, DrifterVariant.SAC, eye.add(14, -1.5 - 1.2, 0)),
                place(singleplayer, DrifterVariant.CHAIN, eye.add(16, -1.5 - 1.5, 5)));
        look(context, singleplayer, eye, eye.add(14, -1.0, 0));
        screenshot(context, "drifter-variants-range");
        frame(context);
        ClientChecks.withHudHidden(context, () -> screenshot(context, "drifter-variants-range-nohud"));
        made.forEach(id -> remove(singleplayer, id));
    }

    /**
     * The camera's place for a turn round {@code focus}: the turn is from the front, and the pitch is the camera's
     * elevation.
     */
    private static Vec3 around(Vec3 focus, double distance, Angle angle) {
        double yaw = Math.toRadians(angle.yaw());
        double pitch = Math.toRadians(angle.pitch());
        return focus.add(
                distance * Math.sin(yaw) * Math.cos(pitch),
                distance * Math.sin(pitch),
                distance * Math.cos(yaw) * Math.cos(pitch));
    }

    /**
     * Puts the first player's eye at {@code eye}, turned to look at {@code target}, and waits for the client to be
     * there and the frame to settle.
     */
    private void look(ClientGameTestContext context, TestSingleplayerContext singleplayer, Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
        Vec3 feet = eye.subtract(0, EYE_BLOCKS, 0);
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(TestLayers.level(server, 1), feet.x, feet.y, feet.z, Set.of(), yaw, pitch, true);
        });
        ClientWait.until(
                context,
                "the camera at " + eye,
                client -> client.player != null
                        && client.player.position().distanceToSqr(feet) < 0.01
                        && Math.abs(client.player.getXRot() - pitch) < 0.5f
                        && client.levelRenderer.hasRenderedAllSections());
        context.waitTicks(6); // tick-wait: the light of the lamp, the fog and the dust motes settle in the frame
    }

    private static UUID place(TestSingleplayerContext singleplayer, DrifterVariant variant, Vec3 feet) {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerLevel level = TestLayers.level(server, 1);
            Drifter drifter = TestEntities.create(CreatureRegistry.DRIFTER, level);
            drifter.setNoAi(true);
            drifter.setNoGravity(true);
            drifter.setVariant(variant);
            drifter.setPos(feet);
            drifter.setYRot(0f);
            drifter.setYHeadRot(0f);
            level.addFreshEntity(drifter);
            return drifter.getUUID();
        });
    }

    private static void remove(TestSingleplayerContext singleplayer, UUID id) {
        singleplayer
                .getServer()
                .runOnServer(server ->
                        TestEntities.require(TestLayers.level(server, 1), id).discard());
    }

    private static void lamp(TestSingleplayerContext singleplayer, BlockPos at, boolean on) {
        singleplayer
                .getServer()
                .runOnServer(server -> TestLayers.level(server, 1)
                        .setBlock(
                                at,
                                on
                                        ? Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, LAMP_LEVEL)
                                        : Blocks.AIR.defaultBlockState(),
                                3));
    }

    /** A sealed hall of stone in layer 1, with the test pack's hollows biome and its gas haze all through it. */
    private static void build(ServerLevel level) {
        BlockPos low = new BlockPos(X - HALF_LENGTH - 2, FLOOR_Y - 2, Z - HALF_WIDTH - 2);
        BlockPos high = new BlockPos(X + HALF_LENGTH + 2, FLOOR_Y + HEIGHT + 1, Z + HALF_WIDTH + 2);
        RoomCarver.carve(level, low, high, Blocks.STONE.defaultBlockState());
        RoomCarver.carve(
                level,
                new BlockPos(X - HALF_LENGTH, FLOOR_Y, Z - HALF_WIDTH),
                new BlockPos(X + HALF_LENGTH, FLOOR_Y + HEIGHT - 1, Z + HALF_WIDTH),
                Blocks.AIR.defaultBlockState());
        CommandSourceStack source =
                level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
        level.getServer()
                .getCommands()
                .performPrefixedCommand(
                        source,
                        "fillbiome %d %d %d %d %d %d %s"
                                .formatted(
                                        low.getX(),
                                        low.getY(),
                                        low.getZ(),
                                        high.getX(),
                                        high.getY(),
                                        high.getZ(),
                                        HOLLOWS));
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, HOLLOWS);
        for (BlockPos at : List.of(low.offset(2, 2, 2), new BlockPos(X, FLOOR_Y + 3, Z), high.offset(-2, -2, -2))) {
            if (!level.getBiome(at).is(key)) {
                throw new IllegalStateException("/fillbiome did not put " + at + " in the hollows test biome; it is in "
                        + level.getBiome(at).unwrapKey());
            }
        }
    }
}
