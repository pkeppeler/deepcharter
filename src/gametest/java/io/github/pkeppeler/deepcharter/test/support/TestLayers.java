package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The one place a test or evidence scenario turns a layer and a local Y into a level and a world position. A layer is its
 * number ({@link LayerChain#SURFACE} for the surface); a local Y is a height inside that layer. Tests never name
 * {@code LayerChain.dimension} or a layer's Y themselves ({@code checkFixedPosGate}), so a change in how layers are laid out
 * edits this class only. {@code SURFACE} positions are in the flat test overworld; GameTests reach it through the helper's level.
 *
 * <p>A layer is a Y band of the campaign level (ADR 0059): the overworld when its type is the campaign type, otherwise
 * {@code deepcharter:campaign_sample}, the test data pack's copy of it. A layer never resolves to a layer dimension; {@link #level}
 * fails if it would. The tests of the dimension machinery reach the layer dimensions through {@link #layerDimension},
 * {@link #dimensionBand} and {@link #isInDimensionOf} alone.
 */
public final class TestLayers {
    /** The surface, the overworld of the chain. */
    public static final int SURFACE = LayerChain.SURFACE;

    /** The campaign world's test dimension, which the data pack adds to every world. */
    private static final ResourceKey<Level> SAMPLE =
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", "campaign_sample"));

    private TestLayers() {}

    /** The level key that holds {@code layer} on {@code server}: the overworld for the surface, the campaign level otherwise. */
    public static ResourceKey<Level> dimension(MinecraftServer server, int layer) {
        if (layer == SURFACE) {
            return Level.OVERWORLD;
        }
        return LayerChain.isCampaign(server.overworld()) ? Level.OVERWORLD : SAMPLE;
    }

    /** The server's level for {@code layer}: the overworld for the surface, the campaign level otherwise. Never a layer dimension. */
    public static ServerLevel level(MinecraftServer server, int layer) {
        ServerLevel level = ClientLight.serverLevel(server, dimension(server, layer));
        if (layer != SURFACE && !LayerChain.isCampaign(level)) {
            throw new IllegalStateException("layer " + layer + " resolved to "
                    + level.dimension().identifier() + ", which is not a campaign level");
        }
        return level;
    }

    /** The level for {@code layer} of the server running {@code helper}'s test. */
    public static ServerLevel level(GameTestHelper helper, int layer) {
        return level(helper.getLevel().getServer(), layer);
    }

    /**
     * The band of {@code layer} in {@code level}, which must be the level that holds it: a campaign level for a layer, the overworld for
     * the surface (the whole level, unless it is a campaign level).
     */
    public static LayerChain.Band band(Level level, int layer) {
        if (layer == SURFACE && !LayerChain.isCampaign(level)) {
            if (!level.dimension().equals(Level.OVERWORLD)) {
                throw new IllegalArgumentException("level " + level.dimension() + " is not the overworld");
            }
            return new LayerChain.Band(SURFACE, level.getMinY(), level.getHeight());
        }
        if (!LayerChain.isCampaign(level)) {
            throw new IllegalArgumentException(
                    "level " + level.dimension() + " does not hold layer " + layer + ": it is not a campaign level");
        }
        CampaignBands.Band band = CampaignBands.ofLayer(layer);
        return new LayerChain.Band(layer, band.minY(), band.height());
    }

    /** The band of {@code layer} in the server's level for it. */
    public static LayerChain.Band band(MinecraftServer server, int layer) {
        return band(level(server, layer), layer);
    }

    /** True when block position {@code pos} of {@code level} is in {@code layer}. */
    public static boolean isIn(Level level, BlockPos pos, int layer) {
        boolean holds = LayerChain.isCampaign(level)
                || (layer == SURFACE && level.dimension().equals(Level.OVERWORLD));
        return holds && inBand(band(level, layer), pos.getY());
    }

    private static boolean inBand(LayerChain.Band band, int y) {
        return y >= band.minY() && y <= band.maxY();
    }

    /** True when {@code entity} is in {@code layer}. */
    public static boolean isIn(Entity entity, int layer) {
        return isIn(entity.level(), entity.blockPosition(), layer);
    }

    /** True when {@code client} has a player, and it is in {@code layer}. */
    public static boolean clientIsIn(Minecraft client, int layer) {
        return client.player != null && isIn(client.player, layer);
    }

    /**
     * The layer dimension of {@code layer}, {@code deepcharter:layer_<n>}: the dimension chain the crossing teleport runs on (ADR 0085
     * keeps it as the base of the dimension seam). Every other layer test uses {@link #level}.
     */
    public static ServerLevel layerDimension(MinecraftServer server, int layer) {
        if (layer == SURFACE) {
            throw new IllegalArgumentException("the surface has no layer dimension: it is the overworld");
        }
        return ClientLight.serverLevel(server, LayerChain.dimension(layer));
    }

    /**
     * The one band of a level of the dimension chain, which is the whole level: a layer dimension, or the overworld when it is not a
     * campaign level. Fails for a campaign level, which holds many bands, and for a level outside the chain.
     */
    public static LayerChain.Band dimensionBand(Level level) {
        if (LayerChain.isCampaign(level)) {
            throw new IllegalArgumentException(
                    "level " + level.dimension() + " is a campaign level: ask band(level, layer) for one of its bands");
        }
        return wholeLevelBand(level)
                .orElseThrow(() -> new IllegalArgumentException("level " + level.dimension() + " is not in the chain"));
    }

    /** True when {@code entity} is in a level of the dimension chain whose one band is {@code layer}. */
    public static boolean isInDimensionOf(Entity entity, int layer) {
        return wholeLevelBand(entity.level())
                .filter(band -> band.layer() == layer)
                .isPresent();
    }

    /** The band of a level of the dimension chain that is one band; empty for a campaign level or a level outside the chain. */
    private static Optional<LayerChain.Band> wholeLevelBand(Level level) {
        return LayerChain.isCampaign(level) ? Optional.empty() : LayerChain.bandAt(level, level.getMinY());
    }

    /** The world Y of {@code localY} in {@code layer}: the band's lowest block plus {@code localY}; the surface keeps its own Ys. */
    public static int worldY(int layer, int localY) {
        return layer == SURFACE ? localY : CampaignBands.layer(layer).minY() + localY;
    }

    /** The world Y of a fractional {@code localY} in {@code layer}. */
    public static double worldY(int layer, double localY) {
        return layer == SURFACE ? localY : CampaignBands.layer(layer).minY() + localY;
    }

    /** The world position of ({@code x}, {@code localY}, {@code z}) in {@code layer}. */
    public static BlockPos pos(int layer, int x, int localY, int z) {
        return new BlockPos(x, worldY(layer, localY), z);
    }

    /** The world position, with fractions, of ({@code x}, {@code localY}, {@code z}) in {@code layer}. */
    public static Vec3 vec(int layer, double x, double localY, double z) {
        return new Vec3(x, worldY(layer, localY), z);
    }
}
