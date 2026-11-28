package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ShaftViews;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "explosive-tiers" (#1458): the hole each explosive leaves, side by side, cut open. Five sites stand in a row on the floor of
 * a lit stone room in layer 1. At each the real item is used as a player uses it: dynamite and plastic explosives from the seat of a pod in a pocket of a stone pile (their blast is a cube round the pod's
 * feet row, a slab or two under the floor and the rest above it) on plain stone, a blasting charge and a shaped charge from the seat of a pod over a column of Company rock (the pod is moved clear before the
 * fuse ends), and a thrown charge thrown on foot straight down onto Company rock. Then the rock in front of each site's middle is cut away, so
 * each hole shows as a section, and the camera stands in the cut, one still per site and one of the row. The scenario uses only what main
 * has too, so the same file records the before stills there.
 */
public class ExplosiveTiersScenario extends EvidenceScenario {
    private static final int X = 6200;
    private static final int Z = 6000;
    private static final int FLOOR_Y = LAYER_ROOM_Y;
    /** Sites stand this far apart along X, so a still of one shows little of the next. */
    private static final int SPACING = 16;
    /** Rows of rock under the floor that the room keeps, deeper than any blast. */
    private static final int BED_ROWS = 18;
    /** Rows of Company rock under a shaped charge, deeper than its column. */
    private static final int COLUMN_ROWS = 16;
    /** Rows of Company rock under a blasting charge and a thrown one, deeper than either blast. */
    private static final int SLAB_ROWS = 6;
    /** How far in front of the sections the camera stands, and the row's still. */
    private static final int VIEW_BACK = 13;

    private static final int ROW_VIEW_BACK = 26;
    private static final int FUSE_WAIT_TICKS = 100;
    /** Blocks past the row's ends that the carved room reaches, so its light settles before a pod is seated. */
    private static final int LIGHT_MARGIN = 16;

    /** What each site holds, in the row's order. */
    private enum Site {
        DYNAMITE(Consumable.DYNAMITE, 0),
        PLASTIC(Consumable.PLASTIC_EXPLOSIVES, 0),
        BLASTING(Consumable.BLASTING_CHARGE, SLAB_ROWS),
        SHAPED(Consumable.SHAPED_CHARGE, COLUMN_ROWS),
        THROWN(Consumable.THROWN_CHARGE, SLAB_ROWS);

        final Consumable item;
        final int rockRows;

        Site(Consumable item, int rockRows) {
            this.item = item;
            this.rockRows = rockRows;
        }

        int x() {
            return X + ordinal() * SPACING;
        }

        String still() {
            return "explosive-tiers-" + name().toLowerCase(Locale.ROOT);
        }
    }

    @Override
    protected String name() {
        return "explosive-tiers";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            int lastX = Site.THROWN.x();
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                RoomCarver.carve(
                        one,
                        X - 10,
                        lastX + 10,
                        FLOOR_Y - BED_ROWS,
                        FLOOR_Y + 10,
                        Z - 15,
                        Z + ROW_VIEW_BACK + 4,
                        Blocks.STONE);
                RoomCarver.carve(
                        one,
                        new BlockPos(X - 9, FLOOR_Y, Z - 14),
                        new BlockPos(lastX + 9, FLOOR_Y + 9, Z + ROW_VIEW_BACK + 3),
                        Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL);
                for (int x = X - 6; x <= lastX + 6; x += 8) {
                    for (int z = Z - 12; z <= Z + ROW_VIEW_BACK; z += 8) {
                        one.setBlock(new BlockPos(x, FLOOR_Y + 9, z), Blocks.GLOWSTONE.defaultBlockState(), 3);
                    }
                }
                for (Site site : new Site[] {Site.DYNAMITE, Site.PLASTIC}) {
                    // A pile of stone round the pod, so the blast round its feet row has rock to clear above the
                    // floor too.
                    RoomCarver.carve(one, site.x() - 4, site.x() + 4, FLOOR_Y, FLOOR_Y + 5, Z - 4, Z + 4, Blocks.STONE);
                    RoomCarver.carve(one, site.x() - 1, site.x(), FLOOR_Y, FLOOR_Y + 2, Z - 1, Z, Blocks.AIR);
                }
                for (Site site : Site.values()) {
                    // A charge's bore square (x - 1 to x, z - 1 to z), and a thrown charge's 3 x 3 round x, z.
                    int high = site == Site.THROWN ? 1 : 0;
                    for (int dx = -1; dx <= high; dx++) {
                        for (int dz = -1; dz <= high; dz++) {
                            for (int dy = 1; dy <= site.rockRows; dy++) {
                                one.setBlock(
                                        new BlockPos(site.x() + dx, FLOOR_Y - dy, Z + dz),
                                        HazardBlocks.COMPANY_ROCK.defaultBlockState(),
                                        3);
                            }
                        }
                    }
                }
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X, FLOOR_Y, Z - 9, Set.of(), 0, 0, true);
            });
            awaitWorld(context);
            ClientWait.lightSettled(
                    context, singleplayer.getServer(), 1, (X + lastX) / 2, Z, (lastX - X) / 2 + LIGHT_MARGIN);
            context.waitTicks(20);
            List<PodEntity> pods = new ArrayList<>();
            for (Site site : Site.values()) {
                if (site == Site.THROWN) {
                    throwCharge(context, singleplayer, site);
                } else {
                    pods.add(fromTheSeat(context, singleplayer, site));
                }
            }
            for (int i = 0; i < FUSE_WAIT_TICKS / 4; i++) {
                context.waitTicks(4);
                frame(context);
            }
            // Cut the rock in front of each site's middle away (the +z side, so the row reads west to east from the
            // camera): the holes
            // show as sections.
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                for (PodEntity pod : pods) {
                    pod.discard();
                }
                RoomCarver.carve(
                        one,
                        new BlockPos(X - 9, FLOOR_Y - BED_ROWS + 1, Z + 1),
                        new BlockPos(lastX + 9, FLOOR_Y + 6, Z + ROW_VIEW_BACK + 3),
                        Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SPECTATOR);
            });
            ShaftViews.nightVision(singleplayer.getServer(), true);
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            for (Site site : Site.values()) {
                look(context, singleplayer, site.x() + 0.5, VIEW_BACK);
                ClientChecks.withHudHidden(context, () -> screenshot(context, site.still()));
            }
            look(context, singleplayer, (X + lastX) / 2.0 + 0.5, ROW_VIEW_BACK);
            ClientChecks.withHudHidden(context, () -> screenshot(context, "explosive-tiers-row"));
        }
    }

    /** Seats the player in a fresh pod on the site, uses the item from the seat, and moves the pod clear of a charge's reach. */
    private PodEntity fromTheSeat(ClientGameTestContext context, TestSingleplayerContext singleplayer, Site site) {
        PodEntity pod = singleplayer.getServer().computeOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.stopRiding();
            PodEntity fresh = TestEntities.create(PodRegistry.POD, one);
            fresh.setPos(site.x(), FLOOR_Y, Z);
            one.addFreshEntity(fresh);
            player.teleportTo(one, site.x(), FLOOR_Y, Z, Set.of(), 0, 0, true);
            return fresh;
        });
        ClientWait.arrived(context, singleplayer.getServer());
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            if (!player.startRiding(pod)) {
                throw new AssertionError("the player could not mount the pod at " + site);
            }
        });
        int ticks = 0;
        while (!singleplayer.getServer().computeOnServer(server -> pod.onGround()) && ticks < 200) {
            context.waitTicks(2);
            ticks += 2;
            frame(context);
        }
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            use(player, site.item);
            player.stopRiding();
            // Clear of the charge's reach (3 blocks), on the room's floor: the fuse lives on the pod, so it must
            // stay loaded.
            pod.setPos(site.x(), FLOOR_Y, Z - 9);
            player.teleportTo(player.level(), site.x() + 4, FLOOR_Y, Z - 9, Set.of(), 0, 0, true);
        });
        for (int i = 0; i < 4; i++) {
            context.waitTicks(3);
            frame(context);
        }
        return pod;
    }

    /** Stands the player on the site, on foot, and throws the charge straight down: it lands at their feet. Then steps clear. */
    private void throwCharge(ClientGameTestContext context, TestSingleplayerContext singleplayer, Site site) {
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.stopRiding();
            player.teleportTo(one, site.x() + 0.5, FLOOR_Y, Z + 0.5, Set.of(), 0, 90, true);
        });
        context.waitTicks(10);
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.setXRot(90);
            use(player, site.item);
        });
        context.waitTicks(10);
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(player.level(), site.x() + 0.5, FLOOR_Y, Z - 10, Set.of(), 0, 0, true);
        });
    }

    private static void use(ServerPlayer player, Consumable item) {
        player.getInventory()
                .setItem(player.getInventory().getSelectedSlot(), new ItemStack(RepairRegistry.item(item)));
        if (!player.gameMode
                .useItem(
                        player,
                        player.level(),
                        player.getItemInHand(InteractionHand.MAIN_HAND),
                        InteractionHand.MAIN_HAND)
                .consumesAction()) {
            throw new AssertionError(item + " should work here");
        }
    }

    /** Stands the camera {@code back} blocks in front of the sections at {@code x}, a little above the middle of the deepest hole, looking north. */
    private void look(ClientGameTestContext context, TestSingleplayerContext singleplayer, double x, int back) {
        Vec3 eye = new Vec3(x, FLOOR_Y - 4.0, Z + back);
        Vec3 target = new Vec3(x, FLOOR_Y - 4.0, Z);
        float[] angles = ShaftViews.anglesTo(eye, target);
        singleplayer.getServer().runOnServer(server -> lookFrom(server, eye, angles));
        for (int i = 0; i < 10; i++) {
            context.waitTicks(3);
            frame(context);
        }
    }

    private static void lookFrom(MinecraftServer server, Vec3 eye, float[] angles) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        player.teleportTo(
                TestLayers.level(server, 1),
                eye.x,
                eye.y - player.getEyeHeight(),
                eye.z,
                Set.of(),
                angles[0],
                angles[1],
                true);
    }
}
