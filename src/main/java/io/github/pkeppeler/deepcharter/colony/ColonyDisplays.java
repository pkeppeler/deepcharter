package io.github.pkeppeler.deepcharter.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.SavedState;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;

/**
 * Which block displays of the colony are still wanted, decided from saved data and not from an entity query (m2-rules.md): a query
 * finds only the displays of chunks that are loaded, so a rebuild or a second pair of the Host's hands would leave the displays of
 * a chunk that is not loaded standing beside the new ones.
 *
 * <p>The saved data counts the builds of each colony site (each attempt of {@link ColonyBuilder}, also one that stopped half way) and
 * the pairs of hands given at each statue ({@link FounderStatue#restoreHands}). Each count is scoped to the one place it retires: the
 * pad centre, or the hands position. A display carries the place and the number it was made under as an entity tag. When a display
 * loads: one without a tag in the area a build clears gets the current build's tag, and hands without a tag get the current pair's
 * tag; one whose number is below the place's current number discards itself. A place the data does not know counts 0, so
 * giving the hands at one place never voids the pair at another. The builder and {@code restoreHands} also discard the displays they
 * find loaded, so that those go at once.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable and is written back unchanged; displays are
 * then left as they are, and callbacks never throw ({@link #readable}).
 */
public final class ColonyDisplays extends SavedData {
    public static final int VERSION = 1;
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "colony_displays");
    static final String BUILD_TAG = "deepcharter_colony_build_";
    static final String HANDS_TAG = "deepcharter_host_hands_";

    /** The number of builds or pairs given so far at one place. */
    public record Count(BlockPos site, int number) {
        private static final Codec<Count> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        BlockPos.CODEC.fieldOf("site").forGetter(Count::site),
                        Codec.INT.fieldOf("number").forGetter(Count::number))
                .apply(instance, Count::new));
    }

    /** What is saved: the builds begun at each pad centre, and the pairs of hands given at each hands position. */
    private record Body(List<Count> builds, List<Count> hands) {
        private static final MapCodec<Body> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Count.CODEC.listOf().fieldOf("builds").forGetter(Body::builds),
                        Count.CODEC.listOf().fieldOf("hands").forGetter(Body::hands))
                .apply(instance, Body::new));
    }

    /** The live form of the counts. */
    private record State(Map<BlockPos, Integer> builds, Map<BlockPos, Integer> hands) {}

    private static final Codec<Versioned<Body>> VERSIONED_CODEC = Versioned.codec(VERSION, Body.CODEC);
    /** The codec of the saved data, which never fails to decode. */
    public static final Codec<ColonyDisplays> CODEC =
            VERSIONED_CODEC.xmap(ColonyDisplays::new, ColonyDisplays::versioned);
    // Datafixer type: as for CharterData (ADR 0007), vanilla's fixers find nothing of theirs in a file that carries our
    // own version.
    public static final SavedDataType<ColonyDisplays> TYPE =
            new SavedDataType<>(ID, ColonyDisplays::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final SavedState<State> state;

    public ColonyDisplays() {
        this.state = SavedState.fresh(ID, VERSION, new State(new HashMap<>(), new HashMap<>()));
    }

    private ColonyDisplays(Versioned<Body> loaded) {
        this.state =
                SavedState.load(ID, VERSION, loaded, body -> new State(counts(body.builds()), counts(body.hands())));
    }

    private static Map<BlockPos, Integer> counts(List<Count> list) {
        Map<BlockPos, Integer> counts = new HashMap<>();
        list.forEach(count -> counts.put(count.site().immutable(), count.number()));
        return counts;
    }

    private static List<Count> listOf(Map<BlockPos, Integer> counts) {
        List<Count> list = new ArrayList<>();
        counts.forEach((site, number) -> list.add(new Count(site, number)));
        return list;
    }

    static void init() {
        ServerEntityEvents.ENTITY_LOAD.register(ColonyDisplays::onLoad);
    }

    /** The world's displays record if it is readable; otherwise empty, after logging that once. Never throws. Call on the server thread. */
    static Optional<ColonyDisplays> readable(MinecraftServer server) {
        ColonyDisplays displays = server.getDataStorage().computeIfAbsent(TYPE);
        return displays.state.isReadable() ? Optional.of(displays) : Optional.empty();
    }

    private Versioned<Body> versioned() {
        return state.versioned(live -> new Body(listOf(live.builds()), listOf(live.hands())));
    }

    /** Starts a build of the colony whose pad is centred on {@code centre}: the displays of its earlier builds are stale from now. */
    void beginBuild(BlockPos centre) {
        state.orThrow().builds().merge(centre.immutable(), 1, Integer::sum);
        setDirty();
    }

    /** Gives the Host at {@code site} his hands anew: the hands of its earlier pairs are stale from now, and no other place's. */
    void beginHands(BlockPos site) {
        state.orThrow().hands().merge(site.immutable(), 1, Integer::sum);
        setDirty();
    }

    /** The builds begun at the pad centre so far; 0 for a place the data does not know. */
    int buildsAt(BlockPos centre) {
        return state.orThrow().builds().getOrDefault(centre, 0);
    }

    /** The pairs of hands given at the position so far; 0 for a place the data does not know. */
    int handsAt(BlockPos site) {
        return state.orThrow().hands().getOrDefault(site, 0);
    }

    /** A place and the number a display was made under there. */
    private record Stamp(BlockPos site, int number) {
        static final String TAIL = "(-?\\d+)_(-?\\d+)_(-?\\d+)_(\\d{1,9})";

        String tag(String prefix) {
            return prefix + site.getX() + "_" + site.getY() + "_" + site.getZ() + "_" + number;
        }
    }

    private static final Pattern BUILD_STAMP = Pattern.compile(Pattern.quote(BUILD_TAG) + Stamp.TAIL);
    private static final Pattern HANDS_STAMP = Pattern.compile(Pattern.quote(HANDS_TAG) + Stamp.TAIL);

    private static Stream<Stamp> stampsOf(Entity display, Pattern pattern) {
        return display.entityTags().stream()
                .map(pattern::matcher)
                .filter(Matcher::matches)
                .map(m -> new Stamp(
                        new BlockPos(
                                Integer.parseInt(m.group(1)),
                                Integer.parseInt(m.group(2)),
                                Integer.parseInt(m.group(3))),
                        Integer.parseInt(m.group(4))));
    }

    private static void onLoad(Entity entity, ServerLevel level) {
        if (!(entity instanceof Display.BlockDisplay display)) {
            return;
        }
        Optional<ColonyDisplays> data = readable(level.getServer());
        if (data.isEmpty()) {
            return;
        }
        boolean hands = display.getBlockState().equals(FounderStatue.handsState());
        boolean staleBuild = stampsOf(display, BUILD_STAMP)
                .anyMatch(stamp -> stamp.number() < data.get().buildsAt(stamp.site()));
        boolean stalePair = hands
                && stampsOf(display, HANDS_STAMP)
                        .anyMatch(stamp -> stamp.number() < data.get().handsAt(stamp.site()));
        if (staleBuild || stalePair) {
            display.discard();
            return;
        }
        if (stampsOf(display, BUILD_STAMP).findAny().isEmpty()) {
            clearedPadAround(level.getServer(), display)
                    .ifPresent(centre ->
                            display.addTag(new Stamp(centre, data.get().buildsAt(centre)).tag(BUILD_TAG)));
        }
        if (hands && stampsOf(display, HANDS_STAMP).findAny().isEmpty()) {
            BlockPos site = FounderStatue.markerFor(display.blockPosition());
            display.addTag(new Stamp(site, data.get().handsAt(site)).tag(HANDS_TAG));
        }
    }

    /** The centre of the pad whose build clears the displays where this one stands, if it stands in such a place: the pad, from its ground up. */
    private static Optional<BlockPos> clearedPadAround(MinecraftServer server, Entity display) {
        return Colony.readable(server)
                .flatMap(ColonySite::started)
                .map(ColonySite.Placed::center)
                .filter(centre -> clearedArea(centre).intersects(display.getBoundingBox()));
    }

    /** The volume a build of the colony clears, for a pad centred on {@code centre}: the pad's size, from its ground up. */
    static AABB clearedArea(BlockPos centre) {
        int half = ColonyTuning.DEFAULT.padSize() / 2;
        return new AABB(
                centre.getX() - half,
                centre.getY(),
                centre.getZ() - half,
                centre.getX() + half,
                centre.getY() + ColonyTuning.DEFAULT.clearHeight() + 1,
                centre.getZ() + half);
    }
}
