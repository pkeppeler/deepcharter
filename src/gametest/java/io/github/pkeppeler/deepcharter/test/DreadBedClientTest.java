package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.SoundTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.BiomeAmbientSoundsHandler;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Client GameTest for #1450: the Retained's dread bed. In a sealed dark room a figure takes hold of the player on foot and the bed rises
 * while the biome's drone ducks under it; a torch in hand does not end it (a light keeps the next figure off, it frees no one held); the
 * player boards a pod and a second figure takes the hull, and there is still one bed; the second is struck off and the bed plays on for the
 * first; the first is struck off and the bed fades, stops, and lets the drone come back. The sounds are read from the sound manager's own
 * listener ({@code Heard}, as {@code ContinuityClientTest} does) and its {@code isActive}.
 */
public class DreadBedClientTest implements FabricClientGameTest {
    private static final Identifier DREAD =
            DeepSound.CREATURE_RETAINED_DREAD.event().location();
    private static final int HALF = 7;
    private static final int ROOM_HEIGHT = 6;
    /** How high over the spawn the room is built: clear of the terrain, in the same biome (so the same drone). */
    private static final int ROOM_RISE = 60;
    /** Blocks between the player and each figure when it is set down; the second figure stands on the other side of the pod. */
    private static final int FIGURE_GAP = 5;
    /** A level read as full or as gone: the fades run in steps of 1/40 and 1/60. */
    private static final float FULL = 0.99f;

    private static final float GONE = 0.01f;
    /** Ticks the player holds a torch while held, past the bed's rise: long enough for a light to end the bed if it did. */
    private static final int TORCH_TICKS = 60;

    private static final RandomSource RANDOM = RandomSource.create();

    /** Every sound the engine starts; a looping one is heard again now and then (for its subtitle), so an instance can be here twice. */
    private static final class Heard implements SoundEventListener {
        private final List<SoundInstance> played = new CopyOnWriteArrayList<>();

        @Override
        public void onPlaySound(SoundInstance sound, WeighedSoundEvents events, float range) {
            played.add(sound);
        }

        /** The beds started so far, each once (an instance is its own identity). */
        List<SoundInstance> beds() {
            return played.stream()
                    .filter(s -> s.getIdentifier().equals(DREAD))
                    .distinct()
                    .toList();
        }

        /** The beds the engine plays now. */
        List<SoundInstance> bedsPlaying(Minecraft client) {
            return beds().stream()
                    .filter(s -> client.getSoundManager().isActive(s))
                    .toList();
        }

        /** The level of the biome's loudest playing drone (its own fade times the duck), 0 when none plays. */
        float drone(Minecraft client) {
            return (float) played.stream()
                    .filter(s -> s instanceof BiomeAmbientSoundsHandler.LoopSoundInstance
                            && client.getSoundManager().isActive(s))
                    .mapToDouble(DreadBedClientTest::level)
                    .max()
                    .orElse(0);
        }
    }

    /** A sound's own level, 0 to 1: its volume without the sounds.json volume that {@code getVolume} multiplies in. */
    private static float level(SoundInstance sound) {
        return sound.getVolume()
                / Objects.requireNonNull(sound.getSound(), "<resolved before the engine plays it>")
                        .getVolume()
                        .sample(RANDOM);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        Heard heard = new Heard();
        context.runOnClient(client -> client.getSoundManager().addListener(heard));
        // The most beds the engine played at once, sampled on every wait below.
        AtomicInteger most = new AtomicInteger();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            int[] ids = {0, 0, 0};
            singleplayer.getServer().runOnServer(server -> {
                server.setDifficulty(Difficulty.NORMAL, true);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                BlockPos origin = sealedRoom(player);
                player.teleportTo(
                        player.level(),
                        origin.getX() + 0.5,
                        origin.getY(),
                        origin.getZ() + 0.5,
                        Set.of(),
                        0f,
                        0f,
                        true);
            });
            ClientWait.until(
                    context,
                    "the biome's drone at full",
                    client -> heard.drone(client) >= FULL,
                    client -> "drone " + heard.drone(client));

            // On foot: a figure takes hold of the player, the bed rises and the drone ducks.
            singleplayer.getServer().runOnServer(server -> ids[0] = figure(player(server), -FIGURE_GAP));
            ClientWait.until(
                    context,
                    "the bed at full and the drone ducked, held on foot",
                    client -> watch(client, heard, most)
                            && bedLevel(client, heard) >= FULL
                            && heard.drone(client) <= SoundTuning.DEFAULT.droneDuck() + GONE,
                    client -> state(client, heard));
            require(
                    heard.beds().size() == 1,
                    "one bed started, not " + heard.beds().size());

            // A torch in hand does not end it: a light keeps the next figure off, it frees no one held.
            long[] torchAt = {0};
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = player(server);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
                torchAt[0] = player.level().getGameTime();
            });
            ClientWait.until(
                    context,
                    TORCH_TICKS + " ticks with a torch in hand, the bed still at full",
                    client -> {
                        watch(client, heard, most);
                        require(
                                bedLevel(client, heard) >= FULL,
                                "a torch in hand turned the bed down: " + state(client, heard));
                        return Objects.requireNonNull(client.level).getGameTime() >= torchAt[0] + TORCH_TICKS;
                    },
                    client -> state(client, heard));

            // In a pod: a second figure takes the hull while the first still holds the player. Still one bed.
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = player(server);
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                PodEntity pod = TestEntities.create(PodRegistry.POD, player.level());
                pod.setPos(player.position());
                player.level().addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not board the pod");
                }
                ids[2] = pod.getId();
                ids[1] = figure(player, FIGURE_GAP);
            });
            ClientWait.until(
                    context,
                    "both figures holding, one on the player and one on the pod, and one bed",
                    client -> watch(client, heard, most)
                            && holds(client, ids[0])
                            && holds(client, ids[1])
                            && client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && bedLevel(client, heard) >= FULL,
                    client -> state(client, heard) + ", figures holding " + holds(client, ids[0]) + " "
                            + holds(client, ids[1]));
            require(
                    heard.beds().size() == 1,
                    "a second figure started a second bed: " + heard.beds().size());

            // The hull's figure is struck off: the player is still held, so the bed plays on, the same one.
            strikeOff(singleplayer, ids[1]);
            ClientWait.until(
                    context,
                    "the hull's figure gone on the client",
                    client -> watch(client, heard, most) && !holds(client, ids[1]),
                    client -> state(client, heard));
            context.runOnClient(client -> require(
                    bedLevel(client, heard) >= FULL && heard.beds().size() == 1,
                    "the bed should play on, the same one, while the other figure holds: " + state(client, heard)));

            // The last figure is struck off: the bed fades, not cut, then stops, and the drone comes back.
            strikeOff(singleplayer, ids[0]);
            float[] between = {0};
            ClientWait.until(
                    context,
                    "the bed faded out and stopped, and the drone back at full",
                    client -> {
                        watch(client, heard, most);
                        float bed = bedLevel(client, heard);
                        if (bed > GONE && bed < FULL) {
                            between[0] = bed;
                        }
                        return heard.bedsPlaying(client).isEmpty() && heard.drone(client) >= FULL;
                    },
                    client -> state(client, heard));
            require(between[0] > 0, "the bed was cut, not faded: no level between silence and full was heard");
            require(
                    heard.beds().size() == 1,
                    "no bed starts once no one is held: " + heard.beds().size());
            require(most.get() == 1, "the engine played " + most.get() + " beds at once");
        }
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    /** A sealed stone room over the spawn, dark (no block light, no sky), with its floor at the returned position. */
    private static BlockPos sealedRoom(ServerPlayer player) {
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition().above(ROOM_RISE);
        for (int u = -HALF - 1; u <= HALF + 1; u++) {
            for (int v = -HALF - 1; v <= HALF + 1; v++) {
                for (int y = -1; y <= ROOM_HEIGHT; y++) {
                    boolean shell = y == -1 || y == ROOM_HEIGHT || Math.abs(u) == HALF + 1 || Math.abs(v) == HALF + 1;
                    // room-carver: a closed room of its own stone shell high over the overworld's test spawn, not
                    // layer rock
                    level.setBlock(origin.offset(u, y, v), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
                }
            }
        }
        return origin;
    }

    /** A Retained {@code dx} blocks east of the player, which walks to them (or to the pod they ride). Its id. */
    private static int figure(ServerPlayer player, int dx) {
        ServerLevel level = player.level();
        LamplessFigure figure = TestEntities.mob(CreatureRegistry.LAMPLESS_FIGURE, level);
        figure.setPos(player.position().add(new Vec3(dx, 0, 0)));
        level.addFreshEntity(figure);
        return figure.getId();
    }

    /** A blow from the player: the figure dies and lets go. */
    private static void strikeOff(TestSingleplayerContext singleplayer, int id) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = player(server);
            ServerLevel level = player.level();
            LamplessFigure figure = TestEntities.require(level, id, LamplessFigure.class);
            figure.hurtServer(level, level.damageSources().playerAttack(player), figure.getMaxHealth() * 10);
        });
    }

    /** Whether the client's copy of the figure lives and holds someone. */
    private static boolean holds(Minecraft client, int id) {
        Entity entity = Objects.requireNonNull(client.level).getEntity(id);
        return entity instanceof LamplessFigure figure && figure.isAlive() && figure.isClinging();
    }

    /** The level of the bed the engine plays now, 0 when none plays. */
    private static float bedLevel(Minecraft client, Heard heard) {
        return (float) heard.bedsPlaying(client).stream()
                .mapToDouble(DreadBedClientTest::level)
                .max()
                .orElse(0);
    }

    /** Records how many beds the engine plays at once; always true, so it can lead a wait's condition. */
    private static boolean watch(Minecraft client, Heard heard, AtomicInteger most) {
        most.accumulateAndGet(heard.bedsPlaying(client).size(), Math::max);
        return true;
    }

    private static String state(Minecraft client, Heard heard) {
        return "beds started " + heard.beds().size() + ", playing "
                + heard.bedsPlaying(client).size() + ", bed " + bedLevel(client, heard) + ", drone "
                + heard.drone(client);
    }
}
