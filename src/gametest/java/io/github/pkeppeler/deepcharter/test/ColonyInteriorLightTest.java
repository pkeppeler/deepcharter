package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyLayout;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.test.support.ColonyChunks;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * No vanilla monster spawns inside a colony building, because every cell of every colony interior has block light of at least
 * 1 (a monster needs block light 0 in the overworld). An interior is a room the generator knows (tools/colony/town.py,
 * {@code rooms} in the layout file): the box of air inside a building's walls, from its floor row to the row under its roof. The
 * test reads each room from the layout, checks the room really is a covered box (a floor and a roof under and over every column of
 * it, so a room that grew a hole in the roof would be seen), and then reads the light the world stores in every cell of the room that
 * a block does not fill.
 */
public class ColonyInteriorLightTest {
    /**
     * The brightest block a dark room may hold: its dim glow is invisible light blocks of level 5
     * (tools/colony/parts.py GLOW_LEVEL), so a lamp inside, which gives 14, fails the test. Light that leaks in through the door
     * from the lamp outside is not a block of the room and is not counted.
     */
    // fixed-pos: a block-light level (tools/colony/parts.py GLOW_LEVEL, not in layout.json), not a world Y; the gate
    // matches "CEILING" by name
    private static final int DIM_CEILING = 5;

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void noCellInsideAColonyBuildingIsAtBlockLightZero(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = server.overworld();
        ColonySite.Placed colony = Colony.placed(server)
                .orElseThrow(() -> failure(helper, "the colony was not built when the server started"));
        List<BlockPos> chunks = ColonyChunks.of(colony);
        int[] ticking = {0};
        FarChunks.Deadline deadline = FarChunks.deadline();
        FarChunks.awaitEntityTicking(helper, level, chunks, index -> ticking[0]++);
        helper.succeedWhen(() -> {
            if (ticking[0] < chunks.size()) {
                throw failure(helper, "waiting for the pad's chunks to tick: %s of %s", ticking[0], chunks.size());
            }
            JsonArray rooms = rooms(server);
            if (rooms.isEmpty()) {
                throw failure(helper, "the layout lists no rooms, so nothing says where the colony's interiors are");
            }
            List<String> problems = new ArrayList<>();
            int cells = 0;
            int dark = 0;
            for (JsonElement element : rooms) {
                JsonObject room = element.getAsJsonObject();
                cells += check(level, colony.center(), room, problems);
                if (room.get("dark").getAsBoolean()) {
                    dark++;
                }
            }
            if (dark == 0) {
                throw failure(helper, "the layout marks no room dark, so nothing says which buildings stay dim");
            }
            if (cells == 0) {
                throw failure(helper, "no room of the layout holds a cell a block does not fill");
            }
            if (!problems.isEmpty()) {
                String message = String.format(
                        "%s problem(s) in %s interior cell(s):\n  %s",
                        problems.size(), cells, String.join("\n  ", problems));
                deadline.await(helper, level, false, () -> message);
                // await-ok: settled is the constant false, so the throw is meant to run; succeedWhen retries it and
                // await's own failure alike, so await's failure past the deadline is the last exception it reports
                throw failure(helper, "%s", message);
            }
        });
    }

    /** Checks one room and returns how many of its cells hold no full block. */
    private static int check(ServerLevel level, BlockPos centre, JsonObject room, List<String> problems) {
        String name = room.get("piece").getAsString();
        BlockPos from = centre.offset(corner(room.getAsJsonArray("from")));
        BlockPos to = centre.offset(corner(room.getAsJsonArray("to")));
        boolean stayDark = room.get("dark").getAsBoolean();
        int open = 0;
        int dark = 0;
        int brightest = 0;
        String first = "";
        for (int x = from.getX(); x <= to.getX(); x++) {
            for (int z = from.getZ(); z <= to.getZ(); z++) {
                if (level.getBlockState(new BlockPos(x, from.getY() - 1, z)).isAir()) {
                    problems.add(name + ": no floor under the room's column at " + x + " " + z);
                }
                if (level.getBlockState(new BlockPos(x, to.getY() + 1, z)).isAir()) {
                    problems.add(name + ": no roof over the room's column at " + x + " " + z);
                }
                for (int y = from.getY(); y <= to.getY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    brightest = Math.max(brightest, state.getLightEmission());
                    if (state.isSolidRender()) {
                        continue;
                    }
                    open++;
                    if (level.getBrightness(LightLayer.BLOCK, pos) < 1) {
                        if (dark++ == 0) {
                            first = pos.toShortString();
                        }
                    }
                }
            }
        }
        if (open == 0) {
            problems.add(
                    name + ": the room " + from.toShortString() + " to " + to.toShortString() + " has no open cell");
        }
        if (dark > 0) {
            problems.add(
                    name + ": " + dark + " of " + open + " open cell(s) are at block light 0, the first at " + first);
        }
        if (stayDark && brightest > DIM_CEILING) {
            problems.add(
                    name + ": a dark room holds a light of " + brightest + ", over its dim glow of " + DIM_CEILING);
        }
        return open;
    }

    private static BlockPos corner(JsonArray array) {
        return new BlockPos(
                array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    private static JsonArray rooms(MinecraftServer server) {
        try (Reader reader =
                server.getResourceManager().getResourceOrThrow(ColonyLayout.ID).openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("rooms");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
