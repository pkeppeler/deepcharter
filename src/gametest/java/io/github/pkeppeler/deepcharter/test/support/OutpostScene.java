package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.outpost.OutpostRegistry;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The scene of the outpost's client test and evidence scenario (#320): the bottom of a shaft at the floor of layer 1, in the world's first player's
 * charter, with the colony's pump and processor repaired, and the outpost kit in the hand. {@link #build} uses the kit, as a player does.
 */
public final class OutpostScene {
    private static final int X = 6200;
    private static final int Z = 6200;
    private static final int BED_RADIUS = 8;
    private static final int SHAFT_HEIGHT = 24;
    /** Dollars the charter starts with: a full tank at the outpost's $2 a litre is $20. */
    public static final long START_BALANCE = 60;

    /** The clicked floor block of the shaft, and the charter. */
    public record Scene(BlockPos floor, CharterId charter) {
        public BlockPos pump() {
            return floor.offset(-2, 1, -4);
        }

        public BlockPos processor() {
            return floor.offset(2, 1, -4);
        }
    }

    private OutpostScene() {}

    /** Stages the shaft bottom and puts the first player at it, a charter founded, the terminals repaired and the kit in hand. */
    public static Scene stage(MinecraftServer server) {
        ServerLevel one = ClientLight.serverLevel(server, LayerChain.dimension(1));
        BlockPos floor = new BlockPos(X, one.getMinY() + LayerTuning.DEFAULT.crustThickness() - 1, Z);
        RoomCarver.carve(
                one,
                floor.offset(-BED_RADIUS, 1, -BED_RADIUS),
                floor.offset(BED_RADIUS, SHAFT_HEIGHT, BED_RADIUS),
                Blocks.STONE.defaultBlockState());
        RoomCarver.carve(one, floor.above(), floor.offset(1, SHAFT_HEIGHT, 1), Blocks.AIR.defaultBlockState());
        // One lamp in the shaft wall, so the bottom reads on screen before the outpost is built.
        one.setBlock(floor.offset(2, 3, 0), Blocks.GLOWSTONE.defaultBlockState(), 3);
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        player.setGameMode(GameType.SURVIVAL);
        if (Charters.found(server, player.getUUID(), "Breach Outfitters").isPresent()) {
            throw new AssertionError("founding the charter should succeed");
        }
        CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                .orElseThrow()
                .id();
        if (Charters.deposit(server, charter, START_BALANCE).isPresent()) {
            throw new AssertionError("the deposit should succeed");
        }
        RepairState repairs = RepairState.get(server);
        for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
            type.parts().forEach(part -> repairs.insert(type, part));
        }
        player.teleportTo(one, floor.getX() + 1.0, floor.getY() + 1, floor.getZ() + 1.0, Set.of(), 0f, 0f, true);
        player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(OutpostRegistry.KIT));
        return new Scene(floor, charter);
    }

    /** Uses the kit in the first player's hand on the floor block, as a click on its top does. */
    public static void build(MinecraftServer server, Scene scene) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ItemStack kit = player.getItemInHand(InteractionHand.MAIN_HAND);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(scene.floor()), Direction.UP, scene.floor(), false);
        if (!kit.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction()) {
            throw new AssertionError("the outpost kit should build at " + scene.floor());
        }
    }

    /** Puts the first player in the hall, {@code yaw} degrees, looking {@code pitch}. */
    public static void standIn(MinecraftServer server, Scene scene, double dx, double dz, float yaw, float pitch) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        player.teleportTo(
                ClientLight.serverLevel(server, LayerChain.dimension(1)),
                scene.floor().getX() + dx,
                scene.floor().getY() + 1,
                scene.floor().getZ() + dz,
                Set.of(),
                yaw,
                pitch,
                true);
    }

    /** A pod of the charter parked in the hall at ({@code dx}, {@code dz}), with {@code fuelPercent} of its tank. */
    public static PodEntity parkPod(MinecraftServer server, Scene scene, double dx, double dz, float fuelPercent) {
        ServerLevel one = ClientLight.serverLevel(server, LayerChain.dimension(1));
        PodEntity pod = TestEntities.create(PodRegistry.POD, one);
        pod.setPos(
                scene.floor().getX() + dx,
                scene.floor().getY() + 1,
                scene.floor().getZ() + dz);
        one.addFreshEntity(pod);
        PodComponents.register(pod, scene.charter());
        pod.setFuel(fuelPercent);
        return pod;
    }
}
