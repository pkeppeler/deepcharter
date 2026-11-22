package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.HangarConsole;
import io.github.pkeppeler.deepcharter.test.support.TestColony;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Evidence scenario "crt-terminals" for #857: every colony terminal, the two-block Company cabinet with its CRT monitor, close up from
 * the square it faces and then from three quarters, and the row of them standing on the ground. Positions come from the colony's anchors and the
 * placed hangar console.
 */
public class CrtTerminalsScenario extends EvidenceScenario {
    private static final int SETTLE_TICKS = 60;
    private static final double EYE = 1.62;
    private static final double FRONT_DISTANCE = 2.6;
    private static final double QUARTER_DISTANCE = 3.0;
    private static final double ROW_END_DISTANCE = 14;
    private static final double ROW_OUT = 10;
    private static final int[] ROW_LIFTS = {3, 6, 9, 12};
    private static final double QUARTER_DEGREES = 40;
    private static final int SWING_FRAMES = 8;
    private static final int TICKS_PER_FRAME = 2;

    @Override
    protected String name() {
        return "crt-terminals";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            EvidencePins.handsHidden = true;
            EvidencePins.itemNameHidden = true;
            try {
                ClientChecks.withHudHidden(context, () -> tour(context, singleplayer));
            } finally {
                EvidencePins.reset();
            }
        }
    }

    private void tour(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        Map<String, Spot> terminals = new LinkedHashMap<>();
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.setPermanentlyInvulnerable(true);
            player.getInventory().clearContent();
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        });
        ColonySite.Placed colony = singleplayer
                .getServer()
                .computeOnServer(server -> Colony.placed(server)
                        .orElseThrow(() -> new AssertionError("the colony was not built when the world started")));
        singleplayer.getServer().runOnServer(server -> {
            for (TerminalType type : TerminalTypes.all()) {
                ColonyAnchor.forTerminal(type)
                        .ifPresent(anchor ->
                                terminals.put(type.id().getPath(), spot(server, TestColony.anchor(colony, anchor))));
            }
            HangarConsole.find(server).ifPresent(pos -> terminals.put("hangar_console", spot(server, pos)));
        });
        Set<String> missing = new TreeSet<>();
        for (TerminalType type : TerminalTypes.all()) {
            if (type.id().getNamespace().equals(DeepCharter.MOD_ID)
                    && !terminals.containsKey(type.id().getPath())) {
                missing.add(type.id().getPath());
            }
        }
        if (!missing.isEmpty()) {
            throw new AssertionError("no terminal was found in the colony for " + missing);
        }
        for (Map.Entry<String, Spot> terminal : terminals.entrySet()) {
            Spot spot = terminal.getValue();
            Vec3 centre = Vec3.atCenterOf(spot.pos());
            // Both halves are in the frame: the aim is the seam between the cabinet and the monitor, a little above.
            Vec3 screen = centre.add(0, 0.9, 0);
            // The terminal's own facing says which side the screen is on.
            Vec3 out = new Vec3(spot.facing().getStepX(), 0, spot.facing().getStepZ());
            Vec3 front = centre.add(out.scale(FRONT_DISTANCE)).add(0, 1.2, 0);
            Vec3 threeQuarter = centre.add(
                            out.yRot((float) Math.toRadians(QUARTER_DEGREES)).scale(QUARTER_DISTANCE))
                    .add(0, 1.7, 0);
            look(singleplayer, front, screen);
            context.waitTicks(SETTLE_TICKS);
            screenshot(context, terminal.getKey() + "-front");
            // The recording: a slow swing from the front round to three quarters, one frame a step.
            for (int i = 0; i < SWING_FRAMES; i++) {
                look(singleplayer, front.lerp(threeQuarter, (double) i / (SWING_FRAMES - 1)), screen);
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            context.waitTicks(SETTLE_TICKS);
            screenshot(context, terminal.getKey() + "-three-quarter");
        }
        // The row from along its end, raised until nothing (the Host's plinth, a building) stands between the camera
        // and any terminal.
        Vec3 rowEye = singleplayer.getServer().computeOnServer(server -> clearViewOfTheRow(server, terminals));
        Vec3 rowMiddle = rowMiddle(terminals);
        look(singleplayer, rowEye, rowMiddle);
        context.waitTicks(SETTLE_TICKS);
        screenshot(context, "terminal-row");
        frame(context);
    }

    /** The terminals of the row on the square: every one but the hangar console. */
    private static Map<String, Spot> row(Map<String, Spot> terminals) {
        Map<String, Spot> row = new LinkedHashMap<>(terminals);
        row.remove("hangar_console");
        return row;
    }

    /** The middle of the row's monitors. */
    private static Vec3 rowMiddle(Map<String, Spot> terminals) {
        Vec3 sum = Vec3.ZERO;
        for (Spot spot : row(terminals).values()) {
            sum = sum.add(Vec3.atCenterOf(spot.pos()));
        }
        return sum.scale(1.0 / row(terminals).size()).add(0, 0.9, 0);
    }

    /**
     * An eye position along the row's axis, a few blocks out in front of it, from either end and at growing heights: the first from
     * which a ray reaches the monitor of every terminal in the row without hitting another block first. Fails when none does.
     */
    private static Vec3 clearViewOfTheRow(MinecraftServer server, Map<String, Spot> terminals) {
        Spot any = row(terminals).values().iterator().next();
        Vec3 out = new Vec3(any.facing().getStepX(), 0, any.facing().getStepZ());
        Vec3 along = new Vec3(
                any.facing().getClockWise().getStepX(),
                0,
                any.facing().getClockWise().getStepZ());
        Vec3 middle = rowMiddle(terminals);
        for (int lift : ROW_LIFTS) {
            for (int end : new int[] {1, -1}) {
                Vec3 eye = middle.add(along.scale(end * ROW_END_DISTANCE))
                        .add(out.scale(ROW_OUT))
                        .add(0, lift, 0);
                if (row(terminals).values().stream().allMatch(spot -> sees(server, eye, spot))) {
                    return eye;
                }
            }
        }
        throw new AssertionError(
                "no clear view of the terminal row from either end, up to " + ROW_LIFTS[ROW_LIFTS.length - 1]);
    }

    private static boolean sees(MinecraftServer server, Vec3 eye, Spot spot) {
        for (int half = 0; half <= 1; half++) {
            BlockPos block = spot.pos().above(half);
            BlockHitResult hit = server.overworld()
                    .clip(new ClipContext(
                            eye,
                            Vec3.atCenterOf(block).add(0, 0, 0),
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE,
                            CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(block)) {
                return false;
            }
        }
        return true;
    }

    private record Spot(BlockPos pos, Direction facing) {}

    private static Spot spot(MinecraftServer server, BlockPos pos) {
        return new Spot(pos, server.overworld().getBlockState(pos).getValue(BlockStateProperties.HORIZONTAL_FACING));
    }

    /** Puts the player's eyes at {@code eye}, looking at {@code target}. */
    private static void look(TestSingleplayerContext singleplayer, Vec3 eye, Vec3 target) {
        teleportLooking(singleplayer, eye.subtract(0, EYE, 0), eye, target);
    }
}
