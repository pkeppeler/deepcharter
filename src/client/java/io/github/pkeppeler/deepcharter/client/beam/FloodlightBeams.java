package io.github.pkeppeler.deepcharter.client.beam;

import io.github.pkeppeler.deepcharter.client.theme.BeamLook;
import io.github.pkeppeler.deepcharter.client.theme.BeamStyle;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The beams of the colony's floodlights (#248). A floodlight is a plain block with no block entity, so the client finds them itself:
 * once a second it looks for floodlights in the loaded sections within the beams' draw distance (a section whose palette holds no
 * floodlight is skipped without a look at its blocks), and casts each one's beam then. A frame costs no raycast: it draws the length
 * found at the last look, and checks that the block is still a floodlight (one block lookup each). A floodlight built or removed shows
 * or loses its beam within a second.
 */
public final class FloodlightBeams {
    /** Client ticks between looks: one second. */
    private static final int LOOK_TICKS = 20;

    private record Lamp(BlockPos pos, BeamDraw beam, double x, double y, double z) {}

    private static List<Lamp> lamps = new ArrayList<>();
    private static int ticks;

    private FloodlightBeams() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(FloodlightBeams::tick);
        LevelRenderEvents.COLLECT_SUBMITS.register(FloodlightBeams::submit);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> lamps = new ArrayList<>());
    }

    private static void tick(Minecraft client) {
        ClientLevel level = client.level;
        Player player = client.player;
        if (level == null || player == null) {
            return;
        }
        if (ticks++ % LOOK_TICKS == 0) {
            lamps = look(level, player.position());
        }
    }

    private static List<Lamp> look(ClientLevel level, Vec3 around) {
        BeamLook look = BeamLook.current();
        BeamStyle style = look.floodlight();
        int chunkRadius = (int) Math.ceil(look.drawDistance() / 16.0) + 1;
        int centreX = Math.floorDiv((int) around.x, 16);
        int centreZ = Math.floorDiv((int) around.z, 16);
        List<Lamp> found = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = centreX - chunkRadius; cx <= centreX + chunkRadius; cx++) {
            for (int cz = centreZ - chunkRadius; cz <= centreZ + chunkRadius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    if (!section.maybeHas(state -> state.is(ColonyKit.FLOODLIGHT))) {
                        continue;
                    }
                    int baseY = level.getSectionYFromSectionIndex(i) * 16;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState state = section.getBlockState(x, y, z);
                                if (state.is(ColonyKit.FLOODLIGHT)) {
                                    pos.set(cx * 16 + x, baseY + y, cz * 16 + z);
                                    found.add(lampAt(level, pos, state, look, style));
                                }
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    private static Lamp lampAt(ClientLevel level, BlockPos at, BlockState state, BeamLook look, BeamStyle style) {
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        double pitch = style.pitch();
        double dx = BeamAim.dirX(facing.toYRot(), pitch);
        double dy = BeamAim.dirY(pitch);
        double dz = BeamAim.dirZ(facing.toYRot(), pitch);
        double x = at.getX() + 0.5 + facing.getStepX() * style.forward();
        double y = at.getY() + style.height();
        double z = at.getZ() + 0.5 + facing.getStepZ() * style.forward();
        double length = BeamAim.cast(level, x, y, z, dx, dy, dz, style.length());
        BeamDraw beam = new BeamDraw(style);
        beam.aim(style, look.sides(), style.length(), length, x, y, z, dx, dy, dz);
        return new Lamp(at.immutable(), beam, x, y, z);
    }

    /** Whether the block of {@code lamp} is still a floodlight: a beam of one that was broken goes at once, before the next look. */
    private static boolean standing(ClientLevel level, Lamp lamp) {
        return level.getBlockState(lamp.pos()).is(ColonyKit.FLOODLIGHT);
    }

    /**
     * The beam the floodlight at {@code pos} is drawn with, or null when the client has not found a floodlight there (a look comes every
     * second) or the block is no longer one.
     */
    // test-seam: the client test reads the floodlight's beam (found, cut by a wall, gone when broken); the game reads
    // the list only to draw it
    public static @Nullable BeamDraw beamAt(BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        for (Lamp lamp : lamps) {
            if (lamp.pos().equals(pos) && standing(level, lamp)) {
                return lamp.beam();
            }
        }
        return null;
    }

    private static void submit(LevelRenderContext context) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || lamps.isEmpty()) {
            return;
        }
        double drawDistance = BeamLook.current().drawDistance();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        for (int i = 0; i < lamps.size(); i++) {
            Lamp lamp = lamps.get(i);
            double dx = lamp.x() - camera.x;
            double dy = lamp.y() - camera.y;
            double dz = lamp.z() - camera.z;
            if (dx * dx + dy * dy + dz * dz > drawDistance * drawDistance || !standing(level, lamp)) {
                continue;
            }
            lamp.beam().moveTo(dx, dy, dz);
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), BeamDraw.renderType(), lamp.beam());
        }
    }
}
