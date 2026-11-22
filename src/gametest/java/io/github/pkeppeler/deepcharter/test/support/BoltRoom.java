package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
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

    private BoltRoom() {}

    /** A corridor starting at {@code x}; its chunk is forced, and ticks entities once {@code ready}. Roof is the row of stone over it. */
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
     * Cuts the corridor of chunk column {@code k} of the tests, and waits for its chunk to tick entities ({@code ready}). Its x is one past a
     * chunk edge, so a corridor up to 14 long lies in one chunk. Call it from the test method.
     */
    public static Corridor corridor(GameTestHelper helper, int length, int k) {
        Corridor corridor = cut(TestLayers.level(helper, 1), length, k);
        FarChunks.awaitEntityTicking(
                helper,
                corridor.level(),
                corridor.floor(0),
                () -> corridor.ready().set(true));
        return corridor;
    }

    /** As {@link #corridor}, for a test that manages its own chunk loading: {@code ready} never arrives. */
    public static Corridor cut(ServerLevel level, int length, int k) {
        int x = FIRST_X + 16 * k + 1;
        RoomCarver.carve(level, x - 3, x + length + 2, FLOOR - 3, FLOOR + 6, Z - 3, Z + 3, Blocks.STONE);
        RoomCarver.carve(level, x, x + length - 1, FLOOR, FLOOR + 1, Z, Z, Blocks.AIR);
        return new Corridor(level, x, length, new Arrival<>("the corridor", "the far chunk ticks"));
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
}
