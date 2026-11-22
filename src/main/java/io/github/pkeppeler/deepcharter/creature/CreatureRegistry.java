package io.github.pkeppeler.deepcharter.creature;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class CreatureRegistry {
    private static final ResourceKey<EntityType<?>> LAMPLESS_FIGURE_KEY = ResourceKey.create(
            Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "lampless_figure"));

    /** A Retained (ADR 0046). A monster for the game's spawner, which the layer biomes list; it never attacks. Not saved, so there is nothing to version. */
    public static final EntityType<LamplessFigure> LAMPLESS_FIGURE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            LAMPLESS_FIGURE_KEY,
            EntityType.Builder.<LamplessFigure>of(LamplessFigure::new, MobCategory.MONSTER)
                    .sized(0.6f, 1.95f)
                    .noSave()
                    .fireImmune()
                    .clientTrackingRange(8)
                    .build(LAMPLESS_FIGURE_KEY));

    private static final ResourceKey<EntityType<?>> DRIFTER_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "drifter"));

    /**
     * A Drifter (#973): a pod-sized spore bladder. The game's spawner never makes one: {@link Drifters} places them, in the biomes of the
     * {@link Drifters#FUNGAL_HOLLOWS} tag. Not saved, so there is nothing to version. The entity type tag {@code deepcharter:sonar_echo/empty_cave}
     * names it as the thing sonar reads as empty cave.
     */
    public static final EntityType<Drifter> DRIFTER = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            DRIFTER_KEY,
            EntityType.Builder.<Drifter>of(Drifter::new, MobCategory.MONSTER)
                    .sized(2.0f, 2.0f)
                    .noSave()
                    .fireImmune()
                    .clientTrackingRange(10)
                    .build(DRIFTER_KEY));

    private static final ResourceKey<EntityType<?>> TALLY_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "tally"));

    /** A Tally (#917). Made by {@link Tallies}, never by the game's spawner, and not saved, so there is nothing to version. */
    public static final EntityType<Tally> TALLY = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            TALLY_KEY,
            EntityType.Builder.<Tally>of(Tally::new, MobCategory.MONSTER)
                    .sized(0.5f, 0.4f)
                    .notInPeaceful()
                    .noSave()
                    .fireImmune()
                    .clientTrackingRange(8)
                    .build(TALLY_KEY));

    private CreatureRegistry() {}

    public static void register() {
        LamplessFigure.init();
        FabricDefaultAttributeRegistry.register(LAMPLESS_FIGURE, LamplessFigure.attributes());
        Drifter.init();
        FabricDefaultAttributeRegistry.register(DRIFTER, Drifter.attributes());
        Tally.init();
        FabricDefaultAttributeRegistry.register(TALLY, Tally.attributes());
    }
}
