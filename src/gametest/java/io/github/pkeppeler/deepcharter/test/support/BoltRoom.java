package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What the bolt driver's tests and its pacing test share (#987): a corridor 1 wide and 2 high cut in a bed of stone in layer 1, a person
 * on foot in it with the driver and a pack of bolts, and the two right clicks of the driver.
 */
public final class BoltRoom {
    private static final int Z = 7400;
    private static final int FLOOR = TestLayers.worldY(1, 60);
    private static final int FIRST_X = 3200;
    /** Blocks of stone bed before a corridor's first cell, and after its last. */
    private static final int BED_MARGIN = 3;
    /**
     * How far apart the corridors of two tests lie. A bed reaches {@link #BED_MARGIN} blocks past each end of its corridor, so at stride 32
     * a 14-long corridor's bed ends 12 blocks short of the next corridor's bed. At 16 a neighbour's bed filled the first cell of the next
     * corridor with stone, and a shooter there fired from inside rock (#1065). A corridor longer than that spans the slots after its own:
     * {@link #cut} refuses a slot whose bed meets another's.
     */
    private static final int STRIDE = 32;

    /** The bed of each slot cut so far, as {first x, last x}; a slot cut again replaces its own. */
    private static final Map<Integer, int[]> BEDS = new ConcurrentHashMap<>();

    private BoltRoom() {}

    /** A corridor starting at {@code x}; its chunks tick entities once {@code ready}. Roof is the row of stone over it. */
    public record Corridor(ServerLevel level, int x, int length, Arrival<Boolean> ready) {
        public BlockPos floor(int along) {
            return new BlockPos(x + along, FLOOR, Z);
        }

        public BlockPos roof(int along) {
            return floor(along).above(2);
        }

        public Vec3 stand(int along) {
            return Vec3.atBottomCenterOf(floor(along));
        }

        public boolean standing(int along) {
            return level.getBlockState(roof(along)).is(Blocks.STONE);
        }
    }

    /**
     * Cuts the corridor of slot {@code k} of the tests, and waits for its chunk to tick entities ({@code ready}). Its x is one past a
     * chunk edge, so a corridor up to 14 long lies in one chunk. Call it from the test method.
     */
    public static Corridor corridor(GameTestHelper helper, int length, int k) {
        Corridor corridor = cut(TestLayers.level(helper, 1), length, k);
        // Every chunk the carved bed covers, not the first alone: a test works along the whole corridor (#1083).
        List<BlockPos> chunks = new ArrayList<>();
        for (int chunkX = bedStart(corridor.x()) >> 4; chunkX <= bedEnd(corridor.x(), length) >> 4; chunkX++) {
            chunks.add(new BlockPos(chunkX << 4, FLOOR, Z));
        }
        FarChunks.awaitAllEntityTicking(
                helper, corridor.level(), chunks, () -> corridor.ready().set(true));
        return corridor;
    }

    /** As {@link #corridor}, for a test that manages its own chunk loading: {@code ready} never arrives. */
    public static Corridor cut(ServerLevel level, int length, int k) {
        int x = FIRST_X + STRIDE * k + 1;
        int bedStart = bedStart(x);
        int bedEnd = bedEnd(x, length);
        for (Map.Entry<Integer, int[]> other : BEDS.entrySet()) {
            int[] bed = other.getValue();
            if (other.getKey() != k && bed[0] <= bedEnd && bedStart <= bed[1]) {
                throw new IllegalArgumentException("slot %d's bed (x %d to %d) meets slot %d's bed (x %d to %d)"
                        .formatted(k, bedStart, bedEnd, other.getKey(), bed[0], bed[1]));
            }
        }
        BEDS.put(k, new int[] {bedStart, bedEnd});
        RoomCarver.carve(level, bedStart, bedEnd, FLOOR - 3, FLOOR + 6, Z - 3, Z + 3, Blocks.STONE);
        RoomCarver.carve(level, x, x + length - 1, FLOOR, FLOOR + 1, Z, Z, Blocks.AIR);
        return new Corridor(level, x, length, new Arrival<>("the corridor", "the far chunk ticks"));
    }

    private static int bedStart(int x) {
        return x - BED_MARGIN;
    }

    private static int bedEnd(int x, int length) {
        return x + length - 1 + BED_MARGIN;
    }

    /** A survival player on foot in the corridor, {@code along} blocks from its start, facing {@code yaw}. */
    public static MockPlayer walker(GameTestHelper helper, Corridor corridor, String name, int along, float yaw) {
        MockPlayer walker = MockPlayers.join(helper, name);
        walker.player().setGameMode(GameType.SURVIVAL);
        walker.teleportTo(corridor.level(), corridor.stand(along), yaw, 0f);
        return walker;
    }

    /** The driver in the selected slot and {@code bolts} bolts in the next. */
    public static void arm(ServerPlayer player, int bolts) {
        player.getInventory()
                .setItem(
                        player.getInventory().getSelectedSlot(),
                        new ItemStack(RepairRegistry.item(Consumable.BOLT_DRIVER)));
        player.getInventory().setItem(1, new ItemStack(RepairRegistry.item(Consumable.BOLT), bolts));
    }

    public static int bolts(ServerPlayer player) {
        return player.getInventory().countItem(RepairRegistry.item(Consumable.BOLT));
    }

    private static void clearCooldown(ServerPlayer player) {
        player.getCooldowns()
                .removeCooldown(
                        player.getCooldowns().getCooldownGroup(player.getItemInHand(InteractionHand.MAIN_HAND)));
    }

    /** A right click in the air with the driver, with its rest between shots cleared. */
    public static InteractionResult fire(ServerPlayer player) {
        clearCooldown(player);
        return player.gameMode.useItem(
                player, player.level(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
    }

    /** A right click on the underside of {@code roof} with the driver, with its rest cleared. */
    public static InteractionResult setBolt(ServerPlayer player, BlockPos roof) {
        clearCooldown(player);
        return player.gameMode.useItemOn(
                player,
                player.level(),
                player.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(roof).add(0, -0.5, 0), Direction.DOWN, roof, false));
    }

    /** Whether the mock was sent the overlay line under {@code key}. It reads on its own tick, so ask a few ticks after the action. */
    public static boolean told(MockPlayer player, String key) {
        return player.actionBarMessages().stream().anyMatch(message -> isKey(message, key));
    }

    private static boolean isKey(Component message, String key) {
        return message.getContents() instanceof TranslatableContents translatable
                && translatable.getKey().equals(key);
    }

    /** A Retained that stands still at {@code at}. */
    public static LamplessFigure figure(ServerLevel level, Vec3 at) {
        LamplessFigure figure = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, level);
        figure.setPos(at);
        figure.setNoAi(true);
        level.addFreshEntity(figure);
        return figure;
    }

    private static final AtomicInteger CHARTERS = new AtomicInteger();

    /** Founds a charter with {@code founder} and returns its id. */
    public static CharterId charter(GameTestHelper helper, MockPlayer founder) {
        MinecraftServer server = helper.getLevel().getServer();
        Optional<CharterRefusal> refusal =
                Charters.found(server, founder.player().getUUID(), "Roof crew " + CHARTERS.incrementAndGet());
        if (refusal.isPresent()) {
            throw failure(helper, "founding a charter was refused: %s", refusal.get());
        }
        return Charters.charterOfOrThrow(server, founder.player().getUUID())
                .orElseThrow()
                .id();
    }

    /** Runs {@code blast} with the server's PvP rule set to {@code pvp}, and puts it back: the rule is read in the blast. */
    public static void withPvp(MinecraftServer server, boolean pvp, Runnable blast) {
        boolean was = server.getGameRules().get(GameRules.PVP);
        try {
            server.getGameRules().set(GameRules.PVP, pvp, server);
            blast.run();
        } finally {
            server.getGameRules().set(GameRules.PVP, was, server);
        }
    }
}
