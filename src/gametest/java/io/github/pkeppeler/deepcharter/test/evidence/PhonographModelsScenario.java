package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceRegistry;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographBlock;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.TestColony;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Evidence scenario "phonograph-models" for #911: the real models of the phonograph and the Company PA horn. Stills of the colony's
 * phonograph on the counter of the Lamp and Pick, close and in the bar; a craftable phonograph empty and loaded on a slab in the sky; the
 * horn under the roof of the punch clock hall, close and in place; and the wreck phonograph in the corner of PROSPECTOR-0002's bay.
 *
 * <p>Every place comes from an anchor or a structure site. Each still names the block it shows and the state it must have, tries a list of
 * eyes in turn, and takes the first from which a ray to the block hits that block (so a wall in the way is never photographed); if none does,
 * or the block is not what it should be, the scenario fails. The eye is where the still says: the player's feet are put one eye height
 * lower. The HUD and the hands are hidden for the whole run.
 */
public class PhonographModelsScenario extends EvidenceScenario {
    private static final int SETTLE_TICKS = 80;
    /** From the Lamp and Pick's anchor, the counter lies within this many blocks. */
    private static final int COUNTER_REACH = 8;
    /** The slab in the sky is this long (x) and deep (z), the craftable phonographs on it two blocks apart. */
    private static final int SLAB_LENGTH = 9;

    private static final int SLAB_DEPTH = 5;
    /** The player's eye height above the feet. */
    private static final double EYE = 1.62;
    /** The farthest an eye may be from its block: a close still taken from further off is a failed still. */
    private static final double CLOSE = 4;

    /** The same for a still that shows the block in its place. */
    private static final double IN_PLACE = 9;

    /** How far off a block's facing the three-quarter stills stand. */
    private static final double QUARTER_DEGREES = 40;
    /** Where on the block the camera looks: a little above its middle, where the horn's bell is. */
    private static final Vec3 LOOK_AT = new Vec3(0, 0.15, 0);

    @Override
    protected String name() {
        return "phonograph-models";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setPermanentlyInvulnerable(true);
                player.setNoGravity(true);
                // The Handbook in the hand would cover the view.
                player.getInventory().clearContent();
                player.getAbilities().mayfly = true;
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
            });
            EvidencePins.handsHidden = true;
            EvidencePins.itemNameHidden = true;
            EvidencePins.vignetteHidden = true;
            EvidencePins.lightFlickerOff = true;
            try {
                ClientChecks.withHudHidden(context, () -> {
                    colonyPhonograph(context, singleplayer);
                    craftablePhonographs(context, singleplayer);
                    punchClockHorn(context, singleplayer);
                    wreckPhonograph(context, singleplayer);
                });
            } finally {
                EvidencePins.reset();
            }
        }
    }

    /** The colony's, on the counter: close, playing (a cylinder on the mandrel), and from further back in the bar. */
    private void colonyPhonograph(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        BlockPos counter = singleplayer.getServer().computeOnServer(server -> {
            ColonySite.Placed colony = Colony.placed(server)
                    .orElseThrow(() -> new AssertionError("the colony was not built when the world started"));
            ServerLevel overworld = server.overworld();
            BlockPos at = TestColony.anchor(colony, ColonyAnchor.LAMP_AND_PICK);
            for (BlockPos pos : BlockPos.betweenClosed(
                    at.offset(-COUNTER_REACH, -1, -COUNTER_REACH), at.offset(COUNTER_REACH, 5, COUNTER_REACH))) {
                overworld.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL);
                if (overworld.getBlockState(pos).is(PhonographRegistry.COLONY_PHONOGRAPH)) {
                    return pos.immutable();
                }
            }
            throw new AssertionError("no colony phonograph within " + COUNTER_REACH + " blocks of the Lamp and Pick");
        });
        Predicate<BlockState> colonys = state -> state.is(PhonographRegistry.COLONY_PHONOGRAPH);
        Direction front = facing(singleplayer, counter);
        still(
                context,
                singleplayer,
                Level.OVERWORLD,
                counter,
                colonys,
                around(counter, front, 2.0, 0.7),
                CLOSE,
                "colony-phonograph-close");

        singleplayer.getServer().runOnServer(server -> {
            ServerLevel overworld = server.overworld();
            overworld.setBlock(counter, overworld.getBlockState(counter).setValue(PhonographBlock.LOADED, true), 3);
        });
        still(
                context,
                singleplayer,
                Level.OVERWORLD,
                counter,
                state -> colonys.test(state) && state.getValue(PhonographBlock.LOADED),
                around(counter, front, 2.0, 0.7),
                CLOSE,
                "colony-phonograph-playing");
        still(
                context,
                singleplayer,
                Level.OVERWORLD,
                counter,
                state -> colonys.test(state) && state.getValue(PhonographBlock.LOADED),
                threeQuarter(counter, front, 1.8, 0.9),
                CLOSE,
                "colony-phonograph-three-quarter");
        still(
                context,
                singleplayer,
                Level.OVERWORLD,
                counter,
                colonys,
                around(counter, front, 5.5, 1.6),
                IN_PLACE,
                "colony-phonograph-in-the-bar");
    }

    /** Two craftable phonographs on a slab in the overworld's sky, above the colony's centre: empty, and with a cylinder in. */
    private void craftablePhonographs(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        BlockPos[] pair = singleplayer.getServer().computeOnServer(server -> {
            ColonySite.Placed colony = Colony.placed(server)
                    .orElseThrow(() -> new AssertionError("the colony was not built when the world started"));
            ServerLevel overworld = server.overworld();
            BlockPos centre = colony.center().atY(SKY_SLAB_Y);
            BlockPos corner = centre.offset(-SLAB_LENGTH / 2, 0, -SLAB_DEPTH / 2);
            for (int x = 0; x < SLAB_LENGTH; x++) {
                for (int z = 0; z < SLAB_DEPTH; z++) {
                    BlockPos pos = corner.offset(x, 0, z);
                    overworld.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL);
                    overworld.setBlock(pos, Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
                    for (int up = 1; up <= 4; up++) {
                        overworld.setBlock(pos.above(up), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            BlockPos empty = centre.offset(-2, 1, 0);
            BlockPos loaded = centre.offset(2, 1, 0);
            var base = PhonographRegistry.PHONOGRAPH
                    .defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
            overworld.setBlock(empty, base, 3);
            overworld.setBlock(loaded, base.setValue(PhonographBlock.LOADED, true), 3);
            return new BlockPos[] {empty, loaded};
        });
        still(
                context,
                singleplayer,
                Level.OVERWORLD,
                pair[0],
                state -> state.is(PhonographRegistry.PHONOGRAPH) && !state.getValue(PhonographBlock.LOADED),
                threeQuarter(pair[0], Direction.NORTH, 1.6, 0.8),
                CLOSE,
                "phonograph-empty-close");
        still(
                context,
                singleplayer,
                Level.OVERWORLD,
                pair[1],
                state -> state.is(PhonographRegistry.PHONOGRAPH) && state.getValue(PhonographBlock.LOADED),
                threeQuarter(pair[1], Direction.NORTH, 1.6, 0.8),
                CLOSE,
                "phonograph-loaded-close");
    }

    /** The Company horn hangs over the middle of the punch clock hall: seen from the floor, close and from the hall's end. */
    private void punchClockHorn(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        StructureSite site = LayerStructuresScenario.prepare(singleplayer, StructureKind.PUNCH_CLOCK);
        ResourceKey<Level> layer = singleplayer
                .getServer()
                .computeOnServer(server -> TestLayers.dimension(server, StructureKind.PUNCH_CLOCK.layer()));
        BlockPos horn = BlockPos.containing(at(site, 0, 4.5, 0));
        Predicate<BlockState> isHorn = state -> state.is(AmbienceRegistry.COMPANY_HORN);
        // The close eyes hang at the horn block's own Y (half a block under its centre), 2.2 blocks off on a diagonal;
        // the hall's
        // pillars stand at 4 along and 2 across.
        still(context, singleplayer, layer, horn, isHorn, hornEyes(horn), CLOSE, "company-horn-close");
        still(
                context,
                singleplayer,
                layer,
                horn,
                isHorn,
                List.of(at(site, -1, 1.7, -7), at(site, 1, 1.7, 7), at(site, -1, 1.7, 7), at(site, 1, 1.7, -7)),
                IN_PLACE,
                "company-horn-in-the-punch-clock-hall");
    }

    /** The wreck's phonograph stands in the corner of PROSPECTOR-0002's bay, 5 along and 5 across the site's own axes. */
    private void wreckPhonograph(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        StructureSite site = LayerStructuresScenario.prepare(singleplayer, StructureKind.WRECK);
        ResourceKey<Level> layer = singleplayer
                .getServer()
                .computeOnServer(server -> TestLayers.dimension(server, StructureKind.WRECK.layer()));
        BlockPos stand = BlockPos.containing(at(site, 5, 0.5, 5));
        Direction front = singleplayer
                .getServer()
                .computeOnServer(server -> ClientLight.serverLevel(server, layer)
                        .getBlockState(stand)
                        .getValue(HorizontalDirectionalBlock.FACING));
        Predicate<BlockState> wrecks = state -> state.is(PhonographRegistry.WRECK_PHONOGRAPH);
        still(
                context,
                singleplayer,
                layer,
                stand,
                wrecks,
                around(stand, front, 2.0, 0.7),
                CLOSE,
                "wreck-phonograph-close");
        still(
                context,
                singleplayer,
                layer,
                stand,
                wrecks,
                around(stand, front, 4.5, 1.5),
                IN_PLACE,
                "wreck-phonograph-in-the-bay");
    }

    /** Four eyes on the diagonals round the horn block, 2.2 blocks off and half a block under its centre. */
    private static List<Vec3> hornEyes(BlockPos horn) {
        double off = 2.2 / Math.sqrt(2);
        Vec3 centre = Vec3.atCenterOf(horn);
        List<Vec3> eyes = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                eyes.add(new Vec3(centre.x + dx * off, horn.getY(), centre.z + dz * off));
            }
        }
        return eyes;
    }

    private static Direction facing(TestSingleplayerContext singleplayer, BlockPos pos) {
        return singleplayer
                .getServer()
                .computeOnServer(
                        server -> server.overworld().getBlockState(pos).getValue(HorizontalDirectionalBlock.FACING));
    }

    /** Eyes {@code distance} out from the block's centre and {@code up} above it: the way it faces first, then its other three sides. */
    private static List<Vec3> around(BlockPos block, Direction front, double distance, double up) {
        List<Vec3> eyes = new ArrayList<>();
        Direction side = front;
        for (int i = 0; i < 4; i++) {
            Vec3 out = Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(distance);
            eyes.add(Vec3.atCenterOf(block).add(out).add(0, up, 0));
            side = side.getClockWise();
        }
        return eyes;
    }

    /**
     * Eyes {@code distance} out from the block's centre, {@code QUARTER_DEGREES} off the way it faces to one side and then the other, and
     * {@code up} above it, so the horn's flare and the cabinet both show; the straight-on eyes of {@link #around} follow as a fallback.
     */
    private static List<Vec3> threeQuarter(BlockPos block, Direction front, double distance, double up) {
        List<Vec3> eyes = new ArrayList<>();
        double angle = Math.toRadians(QUARTER_DEGREES);
        Vec3 ahead = Vec3.atLowerCornerOf(front.getUnitVec3i());
        Vec3 aside = Vec3.atLowerCornerOf(front.getClockWise().getUnitVec3i());
        for (int sign = 1; sign >= -1; sign -= 2) {
            Vec3 out = ahead.scale(Math.cos(angle))
                    .add(aside.scale(sign * Math.sin(angle)))
                    .scale(distance);
            eyes.add(Vec3.atCenterOf(block).add(out).add(0, up, 0));
        }
        eyes.addAll(around(block, front, distance, up));
        return eyes;
    }

    /**
     * Takes the still {@code name} of {@code block}. The block must be one {@code expected} accepts; the camera goes to the first of
     * {@code eyes} from which a ray to the block hits it, looking at it, and the run fails if there is none, so a wall is never the picture.
     */
    private void still(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            ResourceKey<Level> dimension,
            BlockPos block,
            Predicate<BlockState> expected,
            List<Vec3> eyes,
            double maxDistance,
            String name) {
        Vec3 target = Vec3.atCenterOf(block).add(LOOK_AT);
        Vec3 eye = singleplayer.getServer().computeOnServer(server -> {
            ServerLevel level = ClientLight.serverLevel(server, dimension);
            BlockState state = level.getBlockState(block);
            if (!expected.test(state)) {
                throw new AssertionError(name + ": the block at " + block.toShortString() + " is " + state);
            }
            for (Vec3 candidate : eyes) {
                if (candidate.distanceTo(target) > maxDistance) {
                    continue;
                }
                BlockHitResult hit = level.clip(new ClipContext(
                        candidate,
                        target,
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        CollisionContext.empty()));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block)) {
                    return candidate;
                }
            }
            throw new AssertionError(name + ": none of " + eyes.size() + " eyes within " + maxDistance
                    + " blocks sees the block at " + block.toShortString());
        });
        System.out.println("[phonograph-models] " + name + " target=" + block.toShortString() + " eye="
                + String.format("%.2f,%.2f,%.2f", eye.x, eye.y, eye.z) + " dist="
                + String.format("%.2f", eye.distanceTo(target)) + " fallback=" + (eyes.indexOf(eye) > 0));
        Vec3 d = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.setNoGravity(true);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.teleportTo(
                    ClientLight.serverLevel(server, dimension), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true);
        });
        // The camera must be where the still says and looking where it says.
        ClientWait.until(
                context,
                "the camera at the eye of " + name,
                client -> client.level != null
                        && client.level.dimension().equals(dimension)
                        && client.player != null
                        && client.player.distanceToSqr(eye.x, eye.y - EYE, eye.z) < 0.0001
                        && Math.abs(Mth.wrapDegrees(client.player.getYRot() - yaw)) < 0.01f
                        && Math.abs(client.player.getXRot() - pitch) < 0.01f,
                client -> "the player at " + (client.player == null ? "nowhere" : client.player.position()));
        context.waitTicks(SETTLE_TICKS);
        screenshot(context, name);
        frame(context);
    }
}
