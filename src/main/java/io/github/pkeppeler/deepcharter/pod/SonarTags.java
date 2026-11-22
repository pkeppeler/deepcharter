package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

/**
 * Who the navigator's sonar sees and who hears it (#924, CREATURES.md section 2). Data, so a creature joins with a line of JSON in
 * {@code data/deepcharter/tags/entity_type/}, never a change to the sonar.
 */
public final class SonarTags {
    /** The Sleeper's creatures and gnawers: real things. Each returns an echo at its block, moved above {@link SonarTuning#movingSpeed()}, else ore. */
    public static final TagKey<EntityType<?>> ECHOING = tag("sonar_echoes");
    /** Company machines: they echo as ore whether or not they move, as the pilot's scanner reads them. */
    public static final TagKey<EntityType<?>> ORE_ECHOING = tag("sonar_ore");
    /** Moloch's hollow things (the Retained, masks): no echo. It wins over the two tags above, so a type in a broad tag can still be silenced. */
    public static final TagKey<EntityType<?>> HOLLOW = tag("sonar_hollow");
    /** Creatures a ping draws: the Sleeper's. A ping within {@link SonarTuning#noiseBlocks()} of one sends it walking to where the ping came from. */
    public static final TagKey<EntityType<?>> HEARS = tag("sonar_hears");

    private SonarTags() {}

    private static TagKey<EntityType<?>> tag(String path) {
        return TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }
}
