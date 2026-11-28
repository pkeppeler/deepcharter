package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.datafixers.util.Pair;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence for #1606: the surface under the held sky, the deep night's colours with the dusk's fog distances, on the
 * open plain, into the great pit's crater and at the closest mesa, so a reviewer can judge that the terrain still
 * reads. Each view is shot at two ticks of the sky clock, the old brightest dusk (0) and the playtest's moment (80993):
 * the pairs match, because the sky no longer moves.
 */
public class SurfaceHeldSkyScenario extends EvidenceScenario {
    /** Sky-clock ticks the views are shot at. */
    private static final long[] SKY_TICKS = {0, 80_993};
    /** The great pit's middle, south of the colony (ADR 0036): its floor is the crater. */
    private static final BlockPos PIT = new BlockPos(24, 0, 150);

    private static final ResourceKey<Biome> MESA =
            ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("deepcharter", "mesa_country"));
    private static final int MESA_SEARCH_RADIUS = 6_400;
    private static final int MESA_SEARCH_HSTEP = 32;
    private static final int MESA_SEARCH_VSTEP = 64;
    /** The camera over the mesa: this far south of it, and this far over its top. */
    private static final int MESA_BACK = 70;

    private static final int MESA_RISE = 20;
    private static final int SETTLE_TICKS = 40;

    @Override
    protected String name() {
        return "surface-held-sky";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        TextureTestWorld.open(context, shot -> screenshot(context, shot), world -> {
            world.settleLimit(120);
            Vec3 plain = world.regolithPlain();
            Vec3 pit = world.<Vec3>serverGet(server -> floor(TestLayers.level(server, TestLayers.SURFACE), PIT));
            Vec3 mesa = world.<Vec3>serverGet(server -> closestMesa(server, BlockPos.containing(plain)));
            for (long sky : SKY_TICKS) {
                world.serverDo(server -> server.getCommands()
                        .performPrefixedCommand(
                                server.createCommandSourceStack(), "time of deepcharter:sky set " + sky));
                world.view(0, plain.add(0, 3, -12), plain.add(0, 4, 60), SETTLE_TICKS);
                world.still("plain-sky-" + sky);
                world.view(0, pit.add(0, 18, 36), pit, SETTLE_TICKS);
                world.still("crater-sky-" + sky);
                world.view(0, mesa.add(0, MESA_RISE, MESA_BACK), mesa.add(0, MESA_RISE / 2.0, 0), SETTLE_TICKS);
                world.still("mesa-sky-" + sky);
            }
            frame(context);
        });
    }

    private static Vec3 floor(ServerLevel level, BlockPos column) {
        TextureTestWorld.loadChunks(level, column, 2);
        return Vec3.atBottomCenterOf(level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, column));
    }

    /** The top of the ground at the middle of the closest mesa_country biome to {@code from}. */
    private static Vec3 closestMesa(MinecraftServer server, BlockPos from) {
        ServerLevel level = TestLayers.level(server, TestLayers.SURFACE);
        Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(
                biome -> biome.is(MESA), from, MESA_SEARCH_RADIUS, MESA_SEARCH_HSTEP, MESA_SEARCH_VSTEP);
        if (found == null) {
            throw new AssertionError("no mesa_country biome within " + MESA_SEARCH_RADIUS + " blocks of " + from);
        }
        return floor(level, found.getFirst());
    }
}
