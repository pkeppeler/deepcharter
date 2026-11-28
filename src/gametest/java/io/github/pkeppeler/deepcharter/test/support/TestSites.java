package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Who stages where. Layer tests share one world and stage at fixed columns far from the spawn, so two tests that pick
 * the same column of a layer corrupt each other, and only when they run in one batch (#1178). Every such test has its
 * columns claimed in {@link TestSiteTable}, one box of block columns per room (room, shell and the pod's travel), and
 * {@code TestSitesTest} fails the build's GameTest run when two owners' boxes overlap in one layer, whatever order or
 * batch the tests run in.
 *
 * <p>The claims cannot go stale unnoticed: {@link RoomCarver#carve} and {@link FarChunks#force} call {@link
 * #guardCarve} and {@link #guardChunk}, which fail the calling test when it stages outside its own claims. A test that
 * stages and has no claim at all fails there too, and the build fails on a server test that uses {@code FarChunks} or
 * {@code RoomCarver} and is named nowhere in the table ({@code gradle/gametest.gradle.kts}). To add a site, add a line
 * to the table and run {@code TestSitesTest}.
 *
 * <p>The spawn square ({@link #SPAWN_RADIUS}) is every world's own: the colony, the content surveys. Nothing claims it
 * and nothing there is checked. Dimensions other than the overworld and the campaign sample level are one test's own
 * and are not checked.
 */
public final class TestSites {
    /** Blocks around the origin that every test world shares and that no test claims. */
    public static final int SPAWN_RADIUS = 512;

    private static final String TEST_PACKAGE = TestSites.class.getPackageName().replaceAll("\\.support$", "");

    /**
     * The last built layer. A claim for "the last layer" uses this, never its number, which moves when a layer is
     * built.
     */
    public static final int LAST = CampaignBands.lastLayer();

    /** The first layer past the built ones: the campaign sample's unbuilt depth, which tests carve pockets in too. */
    public static final int UNBUILT = LAST + 1;

    /** One owner's inclusive block columns in one layer; layer 0 is the surface. */
    public record Site(String owner, int layer, int x0, int x1, int z0, int z1) {
        public Site {
            if (x1 < x0 || z1 < z0) {
                throw new IllegalArgumentException(
                        owner + " claims an empty box " + x0 + ".." + x1 + ", " + z0 + ".." + z1);
            }
        }

        boolean overlaps(Site other) {
            return layer == other.layer && x0 <= other.x1 && other.x0 <= x1 && z0 <= other.z1 && other.z0 <= z1;
        }

        boolean contains(int fromX, int toX, int fromZ, int toZ) {
            return x0 <= fromX && toX <= x1 && z0 <= fromZ && toZ <= z1;
        }

        boolean touches(int fromX, int toX, int fromZ, int toZ) {
            return x0 <= toX && fromX <= x1 && z0 <= toZ && fromZ <= z1;
        }

        @Override
        public String toString() {
            return "%s layer %s x %s..%s z %s..%s".formatted(owner, layer, x0, x1, z0, z1);
        }
    }

    /**
     * Whether this is a client GameTest JVM ({@code runClientGameTest}, evidence scenarios): each test starts a world
     * of its own, which nothing else stages in, and it carves from the integrated server thread, where the test's frame
     * is not on the stack. Server GameTests run a dedicated server and are guarded.
     */
    private static final boolean CLIENT_JVM = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;

    /** The owner of the test whose scheduled callback is running, for a callback with no test frame under it. */
    private static final ThreadLocal<String> SCOPED = new ThreadLocal<>();

    private static final List<Site> SITES = new ArrayList<>();
    private static final List<String> EXEMPT = new ArrayList<>();

    static {
        TestSiteTable.fill();
    }

    private TestSites() {}

    /**
     * Claims the block columns {@code x0..x1}, {@code z0..z1} (inclusive) of {@code layer} for the test class named
     * {@code owner} (its simple name; a nested class counts as its outer class).
     */
    static void claim(String owner, int layer, int x0, int x1, int z0, int z1) {
        SITES.add(new Site(owner, layer, x0, x1, z0, z1));
    }

    /** Claims {@code count} boxes, each {@code step} columns east of the one before, the first at {@code x0..x1}. */
    static void row(String owner, int layer, int x0, int x1, int step, int count, int z0, int z1) {
        for (int i = 0; i < count; i++) {
            claim(owner, layer, x0 + i * step, x1 + i * step, z0, z1);
        }
    }

    /** Names a test class that stages only where no claim can say: it is checked by nothing. Say why. */
    static void exempt(String owner, String reason) {
        if (reason.isBlank()) {
            throw new IllegalArgumentException(owner + " is exempt for no reason");
        }
        EXEMPT.add(owner);
    }

    /** Every claim. */
    public static List<Site> sites() {
        return List.copyOf(SITES);
    }

    /** Every owner the table names, claiming or exempt. */
    public static List<String> owners() {
        List<String> owners = new ArrayList<>(EXEMPT);
        SITES.forEach(site -> owners.add(site.owner()));
        return owners.stream().distinct().sorted().toList();
    }

    /** Each pair of boxes of two different owners that share a column of one layer, as one line each. */
    public static List<String> overlaps() {
        return overlapsIn(SITES);
    }

    /** As {@link #overlaps}, for any list of boxes. */
    public static List<String> overlapsIn(List<Site> sites) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < sites.size(); i++) {
            for (int j = i + 1; j < sites.size(); j++) {
                Site a = sites.get(i);
                Site b = sites.get(j);
                if (!a.owner().equals(b.owner()) && a.overlaps(b)) {
                    found.add(a + " overlaps " + b);
                }
            }
        }
        return found;
    }

    /** Fails the calling test when it carves a room outside its claims. Called by {@link RoomCarver}. */
    public static void guardCarve(ServerLevel level, BlockPos min, BlockPos max) {
        guardCarve(level, min, max, stack());
    }

    /** As {@link #guardCarve(ServerLevel, BlockPos, BlockPos)}, for the classes of a call stack, innermost first. */
    public static void guardCarve(ServerLevel level, BlockPos min, BlockPos max, List<Class<?>> stack) {
        // The shell RoomSeal writes is one block outside the room.
        int x0 = min.getX() - 1;
        int x1 = max.getX() + 1;
        int z0 = min.getZ() - 1;
        int z1 = max.getZ() + 1;
        int layer = layerAt((min.getY() + max.getY()) / 2);
        String owner = checkedOwner(
                        level,
                        stack,
                        x0,
                        x1,
                        z0,
                        z1,
                        () -> "carves layer " + layer + " x " + x0 + ".." + x1 + " z " + z0 + ".." + z1)
                .orElse(null);
        if (owner != null
                && SITES.stream()
                        .noneMatch(site ->
                                site.owner().equals(owner) && site.layer() == layer && site.contains(x0, x1, z0, z1))) {
            throw new IllegalStateException(owner + " carves layer " + layer + " x " + x0 + ".." + x1 + " z " + z0
                    + ".." + z1
                    + " (the room and its shell), outside its claims. Claim the site in TestSiteTable: a test that"
                    + " stages where another stages fails only when they share a batch (#1182).");
        }
    }

    /** Fails the calling test when it forces a chunk none of its claims touches. Called by {@link FarChunks}. */
    public static void guardChunk(ServerLevel level, int chunkX, int chunkZ) {
        guardChunk(level, chunkX, chunkZ, stack());
    }

    /** As {@link #guardChunk(ServerLevel, int, int)}, for the classes of a call stack, innermost first. */
    public static void guardChunk(ServerLevel level, int chunkX, int chunkZ, List<Class<?>> stack) {
        int x0 = chunkX * 16;
        int z0 = chunkZ * 16;
        String place = "chunk " + chunkX + ", " + chunkZ + " (x " + x0 + ".." + (x0 + 15) + ", z " + z0 + ".."
                + (z0 + 15) + ")";
        String owner = checkedOwner(level, stack, x0, x0 + 15, z0, z0 + 15, () -> "forces " + place)
                .orElse(null);
        if (owner != null
                && SITES.stream()
                        .noneMatch(site -> site.owner().equals(owner) && site.touches(x0, x0 + 15, z0, z0 + 15))) {
            throw new IllegalStateException(owner + " forces " + place
                    + ", which none of its claims touches. Claim the site in TestSiteTable (#1182).");
        }
    }

    /**
     * The owner whose claims must cover the box, or empty when the box is not checked: another dimension, the spawn
     * square, a client JVM, or an exempt owner. Throws when a checked call has no owner.
     */
    private static Optional<String> checkedOwner(
            ServerLevel level, List<Class<?>> stack, int x0, int x1, int z0, int z1, Supplier<String> what) {
        if (!shared(level) || inSpawn(x0, x1, z0, z1) || CLIENT_JVM) {
            return Optional.empty();
        }
        String owner =
                ownerOf(stack).or(() -> Optional.ofNullable(SCOPED.get())).orElseThrow(() -> unattributed(what.get()));
        return EXEMPT.contains(owner) ? Optional.empty() : Optional.of(owner);
    }

    private static boolean shared(ServerLevel level) {
        return level.dimension().equals(Level.OVERWORLD)
                || level.dimension()
                        .identifier()
                        .equals(Identifier.fromNamespaceAndPath("deepcharter", "campaign_sample"));
    }

    private static boolean inSpawn(int x0, int x1, int z0, int z1) {
        return x0 >= -SPAWN_RADIUS && x1 <= SPAWN_RADIUS && z0 >= -SPAWN_RADIUS && z1 <= SPAWN_RADIUS;
    }

    private static int layerAt(int y) {
        return CampaignBands.bandAt(y).map(band -> band.layer()).orElse(UNBUILT);
    }

    /**
     * Wraps a callback that a test schedules from its own stack, so that what it stages is attributed to that test when
     * it runs later with no test frame under it. Call it where the test's frame is on the stack.
     */
    public static Runnable attributed(Runnable callback) {
        Optional<String> owner = ownerOf(stack());
        if (owner.isEmpty()) {
            return callback;
        }
        return () -> {
            String before = SCOPED.get();
            SCOPED.set(owner.get());
            try {
                callback.run();
            } finally {
                SCOPED.set(before);
            }
        };
    }

    private static IllegalStateException unattributed(String what) {
        return new IllegalStateException(
                "A call " + what + " outside the spawn square could not be attributed to a test"
                        + " class: no frame of its stack is in " + TEST_PACKAGE
                        + " (a callback with no test on the stack?). Make"
                        + " the call from the test's own stack, or run it where the test's frame is on it (#1182).");
    }

    private static List<Class<?>> stack() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.<Class<?>>map(StackWalker.StackFrame::getDeclaringClass)
                        .toList());
    }

    /**
     * The server test class that is staging: the outermost class of the outermost frame in {@code
     * io.github.pkeppeler.deepcharter.test} itself (not its support, mixin or evidence packages), so a helper in that
     * package that a test calls is transparent and the test owns the claim. Empty when no such frame is on the stack.
     */
    public static Optional<String> ownerOf(List<Class<?>> stack) {
        return stack.stream()
                .filter(c -> c.getPackageName().equals(TEST_PACKAGE))
                .map(c -> outer(c).getSimpleName())
                .reduce((inner, outermost) -> outermost);
    }

    private static Class<?> outer(Class<?> c) {
        return c.getEnclosingClass() == null ? c : outer(c.getEnclosingClass());
    }
}
