package io.github.pkeppeler.deepcharter.hangar;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.pkeppeler.deepcharter.command.FeatureCommands;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /deepcharter hangar release <player>}: the operator's way out for a Mole place that nothing frees, such as a pod lost in a
 * way the hangar never saw. It frees the player's place and drops a filed write-off without a refund; the pod, if it exists, stays
 * as it is. A world's operator is trusted with it, as with every command at gamemaster level.
 */
final class HangarCommands {
    private HangarCommands() {}

    static void init() {
        FeatureCommands.register(
                "hangar",
                root -> root.then(Commands.literal("release")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(HangarCommands::release))));
    }

    private static int release(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        Optional<HangarData> data = HangarData.readable(source.getServer());
        if (data.isEmpty()) {
            source.sendFailure(Component.translatable("commands.deepcharter.hangar.release.unreadable"));
            return 0;
        }
        Optional<HangarData.Slot> held = data.get().slotOf(player.getUUID());
        if (held.isEmpty()) {
            source.sendFailure(Component.translatable(
                    "commands.deepcharter.hangar.release.none", player.getName().getString()));
            return 0;
        }
        data.get().release(held.get().pod());
        source.sendSuccess(
                () -> Component.translatable(
                        "commands.deepcharter.hangar.release.success",
                        player.getName().getString()),
                true);
        return 1;
    }
}
