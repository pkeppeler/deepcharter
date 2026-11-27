package io.github.pkeppeler.deepcharter.terminal;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The server-side API of terminals, and the one place a player's request reaches one. Every request is checked first, in this
 * order: the player is within reach ({@link #inReach}), the block is a terminal, the charter data
 * and (for a terminal that needs repair) the repair data are readable, the player is on a charter (for a charter-only
 * terminal), and then what the request needs of the repair state. Unreadable saved data is refused as
 * {@code STATE_UNREADABLE}, never thrown.
 * A refused request changes nothing, and the player is told why.
 * Call everything on the server thread.
 */
public final class Terminals {
    private Terminals() {}

    /**
     * Opens the terminal at {@code pos} for {@code player}. A working terminal sends the client its {@link
     * TerminalView}; an unrepaired one opens its {@link RepairMenu}, where the parts go in. The upper half of a
     * terminal opens its lower half.
     */
    public static Optional<TerminalRefusal> open(ServerPlayer player, BlockPos clicked) {
        BlockPos pos = TerminalBlock.lowerHalf(player.level(), clicked);
        return switch (access(player, pos)) {
            case Access.Denied denied -> refuse(player, denied.refusal());
            case Access.Granted granted -> {
                RepairState state = RepairState.get(player.level().getServer());
                if (state.online(granted.type())) {
                    sendView(player, pos, granted);
                } else {
                    RepairMenu.open(player, pos, granted.type(), unlocked(state, granted.type()));
                }
                TerminalEvents.OPENED.invoker().onOpened(player.level().getServer(), granted.type(), player, pos);
                yield Optional.empty();
            }
        };
    }

    /**
     * Runs {@code action} at the terminal at {@code pos} for {@code player}: it needs a repaired terminal and a handler
     * from {@link TerminalActions#register}.
     */
    public static Optional<TerminalRefusal> act(
            ServerPlayer player, BlockPos pos, Identifier action, CompoundTag args) {
        return switch (access(player, pos)) {
            case Access.Denied denied -> refuse(player, denied.refusal());
            case Access.Granted granted -> {
                Optional<TerminalRefusal> refusal = run(player, granted, pos, action, args);
                if (refusal.isPresent()) {
                    yield refuse(player, refusal.get());
                }
                sendView(player, pos, granted);
                TerminalEvents.ACTED.invoker().onActed(player.level().getServer(), granted.type(), player, action);
                yield Optional.empty();
            }
        };
    }

    /**
     * Whether a terminal of {@code type} runs its actions now: its saved data is readable and it is online. When false
     * it refuses every action (only a part goes in, and only while it is merely unrepaired), so a gate that holds a pod
     * or player until it acts holds only while this is true. Safe on a tick path.
     */
    public static boolean canServe(MinecraftServer server, TerminalType type) {
        return dataReadable(server, type) && RepairState.get(server).online(type);
    }

    /**
     * Whether the saved data a request at a {@code type} terminal reads is readable: the charters, and the repair state
     * for a terminal that needs repair.
     */
    private static boolean dataReadable(MinecraftServer server, TerminalType type) {
        return Charters.isReadable(server)
                && (!type.needsRepair() || RepairState.get(server).isReadable());
    }

    /**
     * The pods parked at the terminal at {@code pos}, nearest first: within {@link TerminalTuning#parkedRadius()} blocks of its
     * middle, and neither flying nor drilling. Safe on both sides, but it knows no owners: the server serves from
     * {@link #parkedPods(ServerLevel, BlockPos, Optional)}, and a pod terminal's screen shows the pod that names ({@link ParkedPodView}).
     */
    public static List<PodEntity> parkedPods(Level level, BlockPos pos) {
        double radius = TerminalTuning.DEFAULT.parkedRadius();
        Vec3 centre = Vec3.atCenterOf(pos);
        // entity-query: 8 blocks round a terminal the player stands within reach of (and the client's own world), so
        // the chunks are entity-ticking
        return level
                .getEntitiesOfClass(PodEntity.class, new AABB(pos).inflate(radius), pod -> isParkedAt(pod, pos))
                .stream()
                .sorted(Comparator.comparingDouble(pod -> pod.position().distanceToSqr(centre)))
                .toList();
    }

    /**
     * Whether {@code pod} is parked at the terminal at {@code pos}: within {@link TerminalTuning#parkedRadius()}
     * blocks of its middle, and neither flying nor drilling.
     */
    public static boolean isParkedAt(PodEntity pod, BlockPos pos) {
        double radius = TerminalTuning.DEFAULT.parkedRadius();
        return !pod.flying()
                && !pod.drilling()
                && pod.position().distanceToSqr(Vec3.atCenterOf(pos)) <= radius * radius;
    }

    /**
     * Whether {@code player} is within reach of the terminal at {@code pos}: the distance a request is served from. A
     * player on foot reaches from their eyes. A player seated in a pod, in any seat (#1447), reaches from the pod's
     * box, and only while the pod stands within {@link TerminalTuning#parkedRadius()} of the terminal, as a parked pod
     * does: a wide pod's corner alone does not reach a terminal that would not serve it.
     */
    public static boolean inReach(ServerPlayer player, BlockPos pos) {
        double range = TerminalTuning.DEFAULT.maxDistance();
        double parked = TerminalTuning.DEFAULT.parkedRadius();
        Vec3 centre = Vec3.atCenterOf(pos);
        return SeatedTerminals.seat(player)
                .map(pod -> pod.getBoundingBox().distanceToSqr(centre) <= range * range
                        && pod.position().distanceToSqr(centre) <= parked * parked)
                .orElseGet(() -> player.getEyePosition().distanceToSqr(centre) <= range * range);
    }

    /**
     * The pods of {@link #parkedPods(Level, BlockPos)} that {@code charter}, the acting player's if on one, may use
     * ({@link PodComponents#mayAccess}). The charter's own pods come first, then unowned ones, each nearest first: a stranger's
     * unowned pod parked nearer never hides the player's own.
     */
    public static List<PodEntity> parkedPods(ServerLevel level, BlockPos pos, Optional<Charter> charter) {
        return parkedPods(level, pos).stream()
                .filter(pod -> PodComponents.mayAccess(pod, charter))
                .sorted(Comparator.comparing(
                        pod -> PodComponents.ownerCharter(pod).isEmpty()))
                .toList();
    }

    private sealed interface Access {
        record Granted(TerminalType type, Optional<Charter> charter) implements Access {}

        record Denied(TerminalRefusal refusal) implements Access {}
    }

    private static Access access(ServerPlayer player, BlockPos pos) {
        if (!inReach(player, pos)) {
            return new Access.Denied(TerminalRefusal.TOO_FAR);
        }
        if (!(player.level().getBlockEntity(pos) instanceof TerminalBlockEntity terminal)) {
            return new Access.Denied(TerminalRefusal.NO_SUCH_TERMINAL);
        }
        TerminalType type = terminal.type();
        MinecraftServer server = player.level().getServer();
        // Gameplay code never throws on unreadable saved data: it refuses, and the data stays as it was.
        if (!dataReadable(server, type)) {
            return new Access.Denied(TerminalRefusal.STATE_UNREADABLE);
        }
        Optional<Charter> charter = Charters.readableCharterOf(server, player.getUUID());
        if (type.access() == TerminalType.Access.CHARTER_ONLY && charter.isEmpty()) {
            return new Access.Denied(TerminalRefusal.NOT_ON_A_CHARTER);
        }
        return new Access.Granted(type, charter);
    }

    /**
     * Puts one {@code part} from the player's inventory into the unrepaired terminal at {@code pos}: the right-click
     * shortcut of the {@link RepairMenu}, which holds the same rules. A refusal takes nothing, and the player is told
     * why.
     */
    public static Optional<TerminalRefusal> insert(ServerPlayer player, BlockPos clicked, Item part) {
        BlockPos pos = TerminalBlock.lowerHalf(player.level(), clicked);
        Optional<TerminalRefusal> refusal = insertRefusal(player, pos, part);
        if (refusal.isEmpty() && takeOne(player.getInventory(), part).isEmpty()) {
            refusal = Optional.of(TerminalRefusal.MISSING_PART);
        }
        if (refusal.isPresent()) {
            return refuse(player, refusal.get());
        }
        commit(player, pos, part);
        return Optional.empty();
    }

    /**
     * Why {@code player} cannot put {@code part} into the terminal at {@code pos} now, or empty when they can. Changes
     * nothing.
     */
    static Optional<TerminalRefusal> insertRefusal(ServerPlayer player, BlockPos pos, Item part) {
        return switch (access(player, pos)) {
            case Access.Denied denied -> Optional.of(denied.refusal());
            case Access.Granted granted ->
                granted.type().needsRepair()
                        ? RepairState.get(player.level().getServer()).check(granted.type(), part)
                        : Optional.of(TerminalRefusal.ALREADY_REPAIRED);
        };
    }

    /**
     * Records {@code part}, already taken from the player, as in the terminal at {@code pos}, and fires {@link
     * TerminalEvents#REPAIRED} if it was the last. Call only after {@link #insertRefusal} found nothing on this tick.
     */
    static void commit(ServerPlayer player, BlockPos pos, Item part) {
        if (!(access(player, pos) instanceof Access.Granted access)) {
            throw new IllegalStateException("a part that passed the check lost its access at " + pos);
        }
        MinecraftServer server = player.level().getServer();
        RepairState state = RepairState.get(server);
        state.insert(access.type(), part).ifPresent(unexpected -> {
            throw new IllegalStateException("a part that passed the check was refused: " + unexpected);
        });
        if (state.repaired(access.type())) {
            TerminalEvents.REPAIRED
                    .invoker()
                    .onRepaired(server, access.type(), access.charter().orElseThrow(), player);
        }
    }

    /**
     * True while {@code player} may still use the repair menu of {@code type} at {@code pos}: access holds and it is
     * still unrepaired.
     */
    static boolean stillOpen(ServerPlayer player, BlockPos pos, TerminalType type) {
        return access(player, pos) instanceof Access.Granted granted
                && granted.type() == type
                && !RepairState.get(player.level().getServer()).online(type);
    }

    /** Parts can go into {@code type}: its prerequisite, if any, is repaired. */
    static boolean unlocked(RepairState state, TerminalType type) {
        return type.prerequisite().map(state::repaired).orElse(true);
    }

    /** Takes one {@code item} from the inventory, or returns empty and changes nothing when there is none. */
    private static Optional<ItemStack> takeOne(Inventory inventory, Item item) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                return Optional.of(inventory.removeItem(slot, 1));
            }
        }
        return Optional.empty();
    }

    private static Optional<TerminalRefusal> run(
            ServerPlayer player, Access.Granted access, BlockPos pos, Identifier action, CompoundTag args) {
        MinecraftServer server = player.level().getServer();
        if (!canServe(server, access.type())) {
            return Optional.of(TerminalRefusal.UNREPAIRED);
        }
        Optional<TerminalAction> handler = TerminalActions.find(access.type(), action);
        if (handler.isEmpty()) {
            return Optional.of(TerminalRefusal.NO_SUCH_ACTION);
        }
        Optional<Component> refusal = handler.get()
                .run(new TerminalAction.Context(
                        server, player, access.charter(), access.type(), pos.immutable(), args));
        if (refusal.isPresent()) {
            player.sendOverlayMessage(refusal.get());
            return Optional.of(TerminalRefusal.ACTION_REFUSED);
        }
        return Optional.empty();
    }

    static Optional<TerminalRefusal> refuse(ServerPlayer player, TerminalRefusal refusal) {
        // An action refused by its own handler has told the player already.
        if (refusal != TerminalRefusal.ACTION_REFUSED) {
            player.sendOverlayMessage(refusal.message());
        }
        TerminalEvents.REFUSED.invoker().onRefused(player, refusal);
        return Optional.of(refusal);
    }

    private static void sendView(ServerPlayer player, BlockPos pos, Access.Granted access) {
        if (ServerPlayNetworking.canSend(player, TerminalViewPayload.TYPE)) {
            MinecraftServer server = player.level().getServer();
            Optional<TerminalFeature> feature =
                    TerminalFeatures.supply(access.type(), server, player, access.charter(), pos);
            List<Component> notices = TerminalNotices.collect(server, player, access.charter(), pos);
            ServerPlayNetworking.send(
                    player,
                    new TerminalViewPayload(
                            new TerminalView(pos.immutable(), access.type().id(), feature, notices)));
        }
    }
}
