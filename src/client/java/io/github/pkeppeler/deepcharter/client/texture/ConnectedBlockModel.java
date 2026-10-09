package io.github.pkeppeler.deepcharter.client.texture;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.model.loading.v1.CustomUnbakedBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.texture.CasingConnections;
import io.github.pkeppeler.deepcharter.texture.CasingConnections.FaceTiles;
import io.github.pkeppeler.deepcharter.texture.CasingConnections.Tile;

/**
 * A connected casing (ADR 0037): a full cube whose faces join the same block beside them, so a wall of it reads as one plate. The
 * code is this one model type; each block that uses it says so in its blockstate file and names its five tile textures there:
 *
 * <pre>{@code
 * "": {"fabric:type": "deepcharter:connected", "tiles": {"alone": "deepcharter:block/conduit", "horizontal": ...,
 *      "vertical": ..., "corner": ..., "centre": ...}}
 * }</pre>
 *
 * <p>Each face is four quarter quads; {@link CasingConnections} picks the tile of each and this draws that tile's matching quarter.
 * The block's item keeps an ordinary model (the "alone" tile on every face).
 */
public final class ConnectedBlockModel implements BlockStateModel {
	public static final Identifier TYPE = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "connected");

	private final Map<Tile, Material.Baked> tiles;
	private final @BakedQuad.MaterialFlags int materialFlags;

	private ConnectedBlockModel(Map<Tile, Material.Baked> tiles) {
		this.tiles = tiles;
		int flags = 0;
		for (Material.Baked material : tiles.values()) {
			if (material.forceTranslucent() || material.sprite().contents().computeTransparency(0, 0, 1, 1).hasTranslucent()) {
				flags |= BakedQuad.FLAG_TRANSLUCENT;
			}
			if (material.sprite().contents().isAnimated()) {
				flags |= BakedQuad.FLAG_ANIMATED;
			}
		}
		this.materialFlags = flags;
	}

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
			Predicate<@Nullable Direction> cullTest) {
		for (Direction face : Direction.values()) {
			if (cullTest.test(face)) {
				continue;
			}
			FaceTiles quarters = CasingConnections.tiles(level, pos, state, face);
			quarter(emitter, face, 0, 0.5f, 0.5f, 1, quarters.topLeft());
			quarter(emitter, face, 0.5f, 0.5f, 1, 1, quarters.topRight());
			quarter(emitter, face, 0, 0, 0.5f, 0.5f, quarters.bottomLeft());
			quarter(emitter, face, 0.5f, 0, 1, 0.5f, quarters.bottomRight());
		}
	}

	/** One quarter of a face, in {@code square}'s face frame (0 to 1, left to right and bottom to top), with the same quarter of the tile. */
	private void quarter(QuadEmitter emitter, Direction face, float left, float bottom, float right, float top, Tile tile) {
		emitter.square(face, left, bottom, right, top, 0);
		// square() puts vertex 0 at the top left, then down, across and up; v runs down the texture.
		emitter.uv(0, left, 1 - top);
		emitter.uv(1, left, 1 - bottom);
		emitter.uv(2, right, 1 - bottom);
		emitter.uv(3, right, 1 - top);
		emitter.materialBake(tiles.get(tile), MutableQuadView.BAKE_NORMALIZED);
		emitter.emit();
	}

	@Override
	public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
	}

	@Override
	public Material.Baked particleMaterial() {
		return tiles.get(Tile.ALONE);
	}

	@Override
	public @BakedQuad.MaterialFlags int materialFlags() {
		return materialFlags;
	}

	/** The five tile textures a blockstate names. */
	public record Tiles(Material alone, Material horizontal, Material vertical, Material corner, Material centre) {
		static final MapCodec<Tiles> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Material.CODEC.fieldOf("alone").forGetter(Tiles::alone),
				Material.CODEC.fieldOf("horizontal").forGetter(Tiles::horizontal),
				Material.CODEC.fieldOf("vertical").forGetter(Tiles::vertical),
				Material.CODEC.fieldOf("corner").forGetter(Tiles::corner),
				Material.CODEC.fieldOf("centre").forGetter(Tiles::centre)).apply(instance, Tiles::new));

		Material of(Tile tile) {
			return switch (tile) {
				case ALONE -> alone;
				case HORIZONTAL -> horizontal;
				case VERTICAL -> vertical;
				case CORNER -> corner;
				case CENTRE -> centre;
			};
		}
	}

	/** The blockstate form: {@code {"fabric:type": "deepcharter:connected", "tiles": {...}}}. */
	public record Unbaked(Tiles tiles) implements CustomUnbakedBlockStateModel, ModelDebugName {
		public static final MapCodec<Unbaked> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Tiles.CODEC.fieldOf("tiles").forGetter(Unbaked::tiles)).apply(instance, Unbaked::new));

		@Override
		public MapCodec<? extends CustomUnbakedBlockStateModel> codec() {
			return CODEC;
		}

		@Override
		public void resolveDependencies(Resolver resolver) {
		}

		@Override
		public BlockStateModel bake(ModelBaker baker) {
			Map<Tile, Material.Baked> baked = new EnumMap<>(Tile.class);
			for (Tile tile : Tile.values()) {
				baked.put(tile, baker.materials().get(tiles.of(tile), this));
			}
			return new ConnectedBlockModel(baked);
		}

		@Override
		public String debugName() {
			return "deepcharter:connected " + tiles;
		}
	}
}
