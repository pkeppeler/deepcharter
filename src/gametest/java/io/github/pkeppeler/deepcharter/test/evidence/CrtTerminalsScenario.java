package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.HangarConsole;
import io.github.pkeppeler.deepcharter.test.support.TestColony;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "crt-terminals" for #857: every colony terminal, the Company cabinet with its CRT monitor, close up from the
 * square it faces (south) and then from three quarters, and the plinths in a row. Positions come from the colony's anchors and the
 * placed hangar console.
 */
public class CrtTerminalsScenario extends EvidenceScenario {
    private static final int SETTLE_TICKS = 60;
    private static final double EYE = 1.62;

    @Override
    protected String name() {
        return "crt-terminals";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            Map<String, BlockPos> terminals = new LinkedHashMap<>();
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
                            .ifPresent(anchor -> terminals.put(type.id().getPath(), TestColony.anchor(colony, anchor)));
                }
                HangarConsole.find(server).ifPresent(pos -> terminals.put("hangar_console", pos));
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
            for (Map.Entry<String, BlockPos> terminal : terminals.entrySet()) {
                Vec3 screen = Vec3.atCenterOf(terminal.getValue()).add(0, 0.25, 0);
                look(singleplayer, screen.add(0, 0.2, 2.6), screen);
                context.waitTicks(SETTLE_TICKS);
                screenshot(context, terminal.getKey() + "-front");
                look(singleplayer, screen.add(-1.9, 0.9, 1.9), screen);
                context.waitTicks(SETTLE_TICKS);
                screenshot(context, terminal.getKey() + "-three-quarter");
            }
            Vec3 plinths =
                    Vec3.atCenterOf(terminals.values().stream().findFirst().orElseThrow());
            look(singleplayer, plinths.add(0, 3, 14), plinths);
            context.waitTicks(SETTLE_TICKS);
            screenshot(context, "plinths");
        }
    }

    /** Puts the player's eyes at {@code eye}, looking at {@code target}. */
    private static void look(TestSingleplayerContext singleplayer, Vec3 eye, Vec3 target) {
        teleportLooking(singleplayer, eye.subtract(0, EYE, 0), eye, target);
    }
}
