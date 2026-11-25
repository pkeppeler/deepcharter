package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
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
    /** Things that read as an empty cave at any speed: CREATURES.md's Drifters, "sonar reads one as empty cave". */
    public static final TagKey<EntityType<?>> CAVE_ECHOING = tag("sonar_cave");
    /** Creatures a ping draws: the Sleeper's. A ping within {@link SonarTuning#noiseBlocks()} of one sends it walking to where the ping came from. */
    public static final TagKey<EntityType<?>> HEARS = tag("sonar_hears");

    /**
     * What a creature of a tag reads as, standing still and moving. Empty is no echo.
     *
     * @param tag    the entity tag
     * @param still  the kind it returns at or under {@link SonarTuning#movingSpeed()}
     * @param moving the kind it returns above it
     */
    record Signature(TagKey<EntityType<?>> tag, Optional<SonarKind> still, Optional<SonarKind> moving) {
        Optional<SonarKind> kind(boolean isMoving) {
            return isMoving ? moving : still;
        }
    }

    /** The one table of tag to kind. The first tag a creature is in decides, so the hollow come first: they veto the rest. */
    private static final List<Signature> TABLE = List.of(
            new Signature(HOLLOW, Optional.empty(), Optional.empty()),
            new Signature(ORE_ECHOING, Optional.of(SonarKind.ORE), Optional.of(SonarKind.ORE)),
            new Signature(CAVE_ECHOING, Optional.of(SonarKind.CAVE), Optional.of(SonarKind.CAVE)),
            new Signature(ECHOING, Optional.of(SonarKind.ORE), Optional.of(SonarKind.MOVED)));

    private SonarTags() {}

    /** The signature that decides what {@code creature} reads as, or empty when it is in none of the tags (and so returns nothing). */
    static Optional<Signature> of(Entity creature) {
        return TABLE.stream().filter(signature -> creature.is(signature.tag())).findFirst();
    }

    private static TagKey<EntityType<?>> tag(String path) {
        return TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }
}
