package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.Drifter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The rooms of the Drifter tests (#973): a closed stone room 13 blocks across and 14 high in the surface band of the test world, with a bore of
 * stone under its floor so that a pod there drills down for as long as a test needs, and a Mole at its east end with a mock pilot aboard. The
 * pilot drills by holding {@link Inputs#SPRINT}. The zone and the biome are not the room's business: a test that needs them builds its own in
 * the campaign world.
 */
public final class DrifterArenas {
    /** The room's half width; with its walls it is inside the one chunk that a test forces. */
    public static final int HALF = 6;

    public static final int HEIGHT = 14;
    /** How deep, below the floor, the bore of stone goes (negative). */
    public static final int BORE_DEPTH = -12;

    public static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 100);
    private static final int FIRST_SLOT_X = 9224;
    private static final int SLOT_Z_SPACING = 64;

    private DrifterArenas() {}

    /** The origin, the floor's centre, of room {@code index}: each test has its own, in a chunk of its own. */
    public static BlockPos slot(int index) {
        return new BlockPos(FIRST_SLOT_X, FLOOR_Y, FIRST_SLOT_X + index * SLOT_Z_SPACING);
    }

    /** A room with a pod and a pilot aboard; a pod is a Mole at the room's east end. */
    public record Arena(ServerLevel level, BlockPos origin, PodEntity pod, MockPlayer pilot) {
        public static Arena build(GameTestHelper helper, ServerLevel level, BlockPos origin) {
            return build(helper, level, origin, PodRegistry.POD);
        }

        /** As above, with a pod of {@code type}: a Prospector has a second seat. */
        public static Arena build(
                GameTestHelper helper, ServerLevel level, BlockPos origin, EntityType<PodEntity> type) {
            RoomCarver.carve(
                    level,
                    origin.offset(-HALF - 1, BORE_DEPTH, -HALF - 1),
                    origin.offset(HALF + 1, HEIGHT, HALF + 1),
                    Blocks.STONE.defaultBlockState());
            RoomCarver.carve(
                    level,
                    origin.offset(-HALF, 0, -HALF),
                    origin.offset(HALF, HEIGHT - 1, HALF),
                    Blocks.AIR.defaultBlockState());
            PodEntity pod = TestEntities.create(type, level);
            pod.setPos(Vec3.atBottomCenterOf(origin.east(HALF - 1)));
            level.addFreshEntity(pod);
            MockPlayer pilot = MockPlayers.join(helper, "drifter-pilot");
            pilot.teleportTo(level, pod.position(), 90f, 0f);
            if (!pilot.player().startRiding(pod)) {
                throw new IllegalStateException("the pilot could not mount the pod");
            }
            return new Arena(level, origin, pod, pilot);
        }

        /** A drifter with its feet at {@code feet}, in the room's air, put in the level. */
        public Drifter drifter(Vec3 feet) {
            Drifter drifter = TestEntities.create(CreatureRegistry.DRIFTER, level);
            drifter.setPos(feet);
            level.addFreshEntity(drifter);
            return drifter;
        }

        /** The point {@code dx} blocks east, {@code up} over the floor and {@code dz} blocks south of the room's middle, as feet. */
        public Vec3 at(double dx, double up, double dz) {
            return Vec3.atBottomCenterOf(origin).add(dx, up, dz);
        }

        public void clear() {
            pilot.leave();
            pod.discard();
        }
    }
}
