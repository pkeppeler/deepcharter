package io.github.pkeppeler.deepcharter.colony;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographBlockEntity;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographRegistry;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.timeline.Timeline;

/**
 * The phonograph on the counter of the Lamp and Pick: the colony's, shared by the whole charter, holding the rack of the colony's
 * cylinders that cannot be taken ({@link PhonographRegistry#COLONY_PHONOGRAPH}). It is set by the build on the counter along the bar's back
 * wall, two up and five south of the bar's anchor, so the saved anchors gain no key. A world whose colony was built before it has none.
 *
 * <p>Rarely, at night, it starts by itself while nobody is near: atmosphere only, never a rule or a reward. Every
 * {@link ColonyTuning#phonographCheckTicks()} ticks it is asked ({@link #autoPlay}); the answer is yes with
 * {@link ColonyTuning#phonographChance()} when it is night, no player is within {@link ColonyTuning#phonographQuietRadius()} blocks but
 * one is within earshot, it is not playing, and it has not started by itself already this night. A night is a stretch of the sky timeline round its darkest point,
 * counted by the timeline's period.
 */
public final class ColonyPhonograph {
    private static final ResourceKey<Timeline> SKY =
            ResourceKey.create(Registries.TIMELINE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sky"));

    /** From the Lamp and Pick's anchor to the top of the counter along its back wall (tools/colony/town.py). */
    private static final BlockPos FROM_ANCHOR = new BlockPos(0, 2, 5);

    private ColonyPhonograph() {}

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(ColonyPhonograph::onTick);
    }

    /** Where the phonograph stands, for a colony with these anchors. */
    static BlockPos position(Map<ColonyAnchor, BlockPos> anchors) {
        return ColonyAnchor.LAMP_AND_PICK.in(anchors).offset(FROM_ANCHOR);
    }

    /** The block, turned to face the door. */
    static BlockState state() {
        return PhonographRegistry.COLONY_PHONOGRAPH
                .defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
    }

    private static void onTick(MinecraftServer server) {
        if (server.getTickCount() % ColonyTuning.DEFAULT.phonographCheckTicks() != 0) {
            return;
        }
        ServerLevel level = server.overworld();
        Colony.anchor(server, ColonyAnchor.LAMP_AND_PICK).ifPresent(anchor -> {
            BlockPos pos = anchor.offset(FROM_ANCHOR);
            // Never loads the pad: with nobody near the chunk, the phonograph has no audience either.
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof PhonographBlockEntity phonograph) {
                autoPlay(level, phonograph, level.getRandom());
            }
        });
    }

    /**
     * One check: starts {@code phonograph} if it may and {@code random} says so. Returns whether it did. The random source is the only
     * chance in it, so a test forces the roll with one that answers 0.
     */
    public static boolean autoPlay(ServerLevel level, PhonographBlockEntity phonograph, RandomSource random) {
        // A world with no sky timeline (a data pack removed it) has no night: skip, never throw on a tick.
        Optional<Timeline> found = level.registryAccess()
                .lookupOrThrow(Registries.TIMELINE)
                .get(SKY)
                .map(Holder.Reference::value);
        if (found.isEmpty() || found.get().periodTicks().isEmpty()) {
            return false;
        }
        Timeline sky = found.get();
        long night = sky.getPeriodCount(level.clockManager());
        if (phonograph.isPlaying()
                || !isNight(sky, level)
                || phonograph.lastAutoNight().filter(last -> last == night).isPresent()
                || someoneNear(level, phonograph.getBlockPos())
                // A start nobody can hear is no start: it would use up the night.
                || !phonograph.hasAudience()
                || random.nextDouble() >= ColonyTuning.DEFAULT.phonographChance()
                || !phonograph.play()) {
            return false;
        }
        if (phonograph.listenerCount() == 0) {
            phonograph.stop();
            return false;
        }
        phonograph.markAutoNight(night);
        return true;
    }

    private static boolean isNight(Timeline sky, ServerLevel level) {
        long period = sky.periodTicks().orElse(1);
        long darkest = period / 2;
        return Math.abs(sky.getCurrentTicks(level.clockManager()) - darkest)
                <= ColonyTuning.DEFAULT.phonographNightHalfWidth();
    }

    private static boolean someoneNear(ServerLevel level, BlockPos pos) {
        double radius = ColonyTuning.DEFAULT.phonographQuietRadius();
        Vec3 at = Vec3.atCenterOf(pos);
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.distanceToSqr(at) <= radius * radius) {
                return true;
            }
        }
        return false;
    }
}
