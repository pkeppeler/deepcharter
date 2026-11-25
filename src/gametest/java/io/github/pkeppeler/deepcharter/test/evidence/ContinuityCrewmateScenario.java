package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "death-sequence-crewmate" for #1337: the Continuity Event as the crew see it. A crewmate in a pick and a chestplate
 * stands in a lit layer-1 chamber, facing the camera a few blocks off, and dies there of a burn. Their body does not fall or flash red: it
 * stands still for half of the dying time, then fades see-through with nothing it wore or held, and goes up in ash and smoke. The client
 * records no sound, so the lamp's click is not in the frames ({@code ContinuityEventTest} checks it). The stills are taken by the dying
 * body's own death time as the client counts it.
 */
public final class ContinuityCrewmateScenario extends EvidenceScenario {
    private static final int X = 5400;
    private static final int Z = 5200;
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    /** The camera's eye is this far from the crewmate, along X. */
    private static final double DISTANCE = 3.5;
    /** Vanilla yaws: the camera looks along +X, the crewmate along -X, at it. */
    private static final float CAMERA_YAW = -90f;

    private static final float CREWMATE_YAW = 90f;
    private static final float LOOK_DOWN = 8f;
    private static final int ALIVE_FRAMES = 20;
    private static final int AFTER_FRAMES = 40;
    private static final int DEATH_LIMIT_TICKS = 200;
    /** The death times of the stills: early in the solid half, and late in the see-through half (the theme's split is at 10 of 20). */
    private static final float STILL_DEATH_TIME = 4f;

    private static final float FADING_DEATH_TIME = 15f;

    @Override
    protected String name() {
        return "death-sequence-crewmate";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            UUID crewmate = singleplayer.getServer().computeOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                box(one, FLOOR_Y - 2, FLOOR_Y - 1, 6, Blocks.STONE);
                box(one, FLOOR_Y, FLOOR_Y + 8, 6, Blocks.STONE);
                // room-carver: a room cut inside the solid stone box built just above, so no worldgen lava is in or
                // beside it
                box(one, FLOOR_Y, FLOOR_Y + 7, 5, Blocks.AIR);
                for (BlockPos lamp : new BlockPos[] {
                    new BlockPos(X + 5, FLOOR_Y + 3, Z),
                    new BlockPos(X - 5, FLOOR_Y + 3, Z),
                    new BlockPos(X, FLOOR_Y + 3, Z + 5),
                    new BlockPos(X, FLOOR_Y + 3, Z - 5)
                }) {
                    one.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
                }
                ServerPlayer camera = server.getPlayerList().getPlayers().getFirst();
                camera.setGameMode(GameType.CREATIVE);
                camera.setPermanentlyInvulnerable(true);
                camera.teleportTo(
                        one, X + 1.5 - DISTANCE, FLOOR_Y, Z + 0.5, Set.of(), CAMERA_YAW, LOOK_DOWN, true);
                MockPlayer mock =
                        MockPlayers.join(server, "Crewmate", one, new Vec3(X + 1.5, FLOOR_Y, Z + 0.5), CREWMATE_YAW);
                ServerPlayer body = mock.player();
                body.setGameMode(GameType.SURVIVAL);
                body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                body.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
                return body.getUUID();
            });
            ClientWait.until(
                    context,
                    "the crewmate in view in layer 1",
                    client -> TestLayers.clientIsIn(client, 1) && crewmateOf(client, crewmate) != null);
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            ClientWait.until(context, "the room drawn", client -> client.levelRenderer.hasRenderedAllSections());
            ClientChecks.withHudHidden(context, () -> {
                for (int i = 0; i < ALIVE_FRAMES; i++) {
                    step(context);
                }
                screenshot(context, "1-crewmate");

                singleplayer.getServer().runOnServer(server -> {
                    ServerPlayer body = server.getPlayerList().getPlayer(crewmate);
                    if (body == null) {
                        throw new AssertionError("the crewmate left");
                    }
                    body.hurtServer(body.level(), body.level().damageSources().lava(), 1000f);
                });
                boolean still = false;
                boolean fading = false;
                for (int i = 0; i < DEATH_LIMIT_TICKS && !fading; i++) {
                    step(context);
                    float deathTime = context.computeOnClient(client -> {
                        Player body = crewmateOf(client, crewmate);
                        return body == null ? -1f : body.deathTime;
                    });
                    if (!still && deathTime >= STILL_DEATH_TIME) {
                        screenshot(context, "2-still-body");
                        still = true;
                    } else if (still && deathTime >= FADING_DEATH_TIME) {
                        screenshot(context, "3-fading");
                        fading = true;
                    }
                }
                if (!still || !fading) {
                    throw new AssertionError("the crewmate's death was not seen: still " + still + ", fading " + fading);
                }
                ClientWait.until(
                        context, "the body gone", client -> crewmateOf(client, crewmate) == null);
                step(context);
                screenshot(context, "4-ash");
                for (int i = 0; i < AFTER_FRAMES; i++) {
                    step(context);
                }
            });
        }
    }

    private void step(ClientGameTestContext context) {
        context.waitTicks(1); // tick-wait: one frame a tick, the recording's frame rate
        frame(context);
    }

    /** The crewmate as the client has them, or null once the body is gone. */
    private static @Nullable Player crewmateOf(Minecraft client, UUID id) {
        return client.level == null ? null : client.level.getPlayerByUUID(id);
    }

    /** A square of {@code block} {@code radius} blocks out from the room's column, from {@code yFrom} to {@code yTo}. */
    private static void box(ServerLevel level, int yFrom, int yTo, int radius, Block block) {
        for (int x = X - radius; x <= X + radius; x++) {
            for (int y = yFrom; y <= yTo; y++) {
                for (int z = Z - radius; z <= Z + radius; z++) {
                    level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }
}
