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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "crt-terminals" for #857: every colony terminal, the Company cabinet with its CRT monitor, close up from the
 * square it faces (south) and then from three quarters, and the plinths in a row. Positions come from the colony's anchors and the
 * placed hangar console.
 */
public class CrtTerminalsScenario extends EvidenceScenario {
    private static final int SETTLE_TICKS = 60;
    private static final double EYE = 1.62;
    private static final double FRONT_DISTANCE = 1.8;
    private static final double QUARTER_DISTANCE = 2.1;
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
            Vec3 screen = centre.add(0, 0.1, 0);
            // The terminal's own facing says which side the screen is on.
            Vec3 out = new Vec3(spot.facing().getStepX(), 0, spot.facing().getStepZ());
            Vec3 front = centre.add(out.scale(FRONT_DISTANCE)).add(0, 0.25, 0);
            Vec3 threeQuarter = centre.add(
                            out.yRot((float) Math.toRadians(QUARTER_DEGREES)).scale(QUARTER_DISTANCE))
                    .add(0, 0.6, 0);
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
        Spot first = terminals.values().stream().findFirst().orElseThrow();
        Vec3 plinths = Vec3.atCenterOf(first.pos());
        Vec3 out = new Vec3(first.facing().getStepX(), 0, first.facing().getStepZ());
        look(singleplayer, plinths.add(out.scale(14)).add(0, 3, 0), plinths);
        context.waitTicks(SETTLE_TICKS);
        screenshot(context, "plinths");
        frame(context);
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
