package io.github.pkeppeler.deepcharter.colony;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The colony's layout, the data file {@code data/deepcharter/colony/layout.json} that tools/colony/build.py writes with the
 * structure pieces it names (ADR 0030): which structure goes where, from the centre of the pad, in the order it is placed, which
 * pieces wait for a work order, and where each {@link ColonyAnchor} is. Every offset is from the pad's centre, which is at ground
 * level; X is east and Z is south. It is read each time the colony is built or a piece is placed, so a data pack replaces it.
 */
public record ColonyLayout(List<Piece> pieces, List<Piece> later, Map<ColonyAnchor, BlockPos> anchors) {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "colony/layout.json");
	/** The Host's hands, which the first work order places ({@link FounderStatue}). */
	public static final Identifier HOST_HANDS = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "colony/host_hands");

	/**
	 * One structure piece. {@code blocks} and {@code displays} are what its file holds, which the tests check.
	 *
	 * @param offset where the piece's lowest corner goes, from the pad's centre
	 */
	public record Piece(Identifier structure, BlockPos offset, int blocks, int displays) {
		private static final Codec<Piece> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Identifier.CODEC.fieldOf("structure").forGetter(Piece::structure),
				BlockPos.CODEC.fieldOf("offset").forGetter(Piece::offset),
				Codec.INT.fieldOf("blocks").forGetter(Piece::blocks),
				Codec.INT.fieldOf("displays").forGetter(Piece::displays)).apply(instance, Piece::new));

		/**
		 * Places the piece, its blocks and its displays, with {@code centre} as the pad's centre.
		 *
		 * @throws IllegalStateException when the structure file does not load
		 */
		void place(ServerLevel level, BlockPos centre) {
			StructureTemplate template = level.getServer().getStructureTemplateManager().get(structure)
					.orElseThrow(() -> new IllegalStateException("the colony's structure " + structure + " does not load"));
			BlockPos at = centre.offset(offset);
			template.placeInWorld(level, at, at, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
		}
	}

	private static final Codec<ColonyLayout> CODEC = RecordCodecBuilder.<ColonyLayout>create(instance -> instance.group(
			Piece.CODEC.listOf().fieldOf("pieces").forGetter(ColonyLayout::pieces),
			Piece.CODEC.listOf().fieldOf("later").forGetter(ColonyLayout::later),
			Codec.unboundedMap(ColonyAnchor.CODEC, BlockPos.CODEC).fieldOf("anchors").forGetter(ColonyLayout::anchors))
			.apply(instance, ColonyLayout::new));

	public ColonyLayout {
		EnumMap<ColonyAnchor, BlockPos> sorted = new EnumMap<>(ColonyAnchor.class);
		sorted.putAll(anchors);
		if (!sorted.keySet().containsAll(List.of(ColonyAnchor.values()))) {
			throw new IllegalArgumentException("the colony's layout names the anchors " + sorted.keySet() + ", not every anchor");
		}
		pieces = List.copyOf(pieces);
		later = List.copyOf(later);
		anchors = Map.copyOf(sorted);
	}

	/**
	 * Reads the layout from the server's data.
	 *
	 * @throws IllegalStateException when the file is missing or does not parse: the colony cannot be built without it
	 */
	public static ColonyLayout read(MinecraftServer server) {
		try (Reader reader = server.getResourceManager().getResourceOrThrow(ID).openAsReader()) {
			return CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow(message -> new IllegalStateException("the colony's layout " + ID + ": " + message));
		} catch (IOException e) {
			throw new UncheckedIOException("the colony's layout " + ID + " cannot be read", e);
		}
	}

	/** Every anchor's position when the pad's centre is at {@code centre}. */
	Map<ColonyAnchor, BlockPos> anchorsAt(BlockPos centre) {
		EnumMap<ColonyAnchor, BlockPos> at = new EnumMap<>(ColonyAnchor.class);
		anchors.forEach((anchor, offset) -> at.put(anchor, centre.offset(offset)));
		return at;
	}

	/** The piece that waits for a work order, by its structure, or throws when the layout holds no such piece. */
	Piece later(Identifier structure) {
		return later.stream().filter(piece -> piece.structure().equals(structure)).findFirst()
				.orElseThrow(() -> new IllegalStateException("the colony's layout holds no later piece " + structure));
	}
}
