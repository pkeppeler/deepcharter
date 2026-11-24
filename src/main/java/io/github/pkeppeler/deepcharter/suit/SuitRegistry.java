package io.github.pkeppeler.deepcharter.suit;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

/** Registers the suit's attachment and names its damage type ({@code data/deepcharter/damage_type/suit_exposure.json}). */
public final class SuitRegistry {
    /** Per player, persistent, kept on death and sent to its owner alone. */
    public static final AttachmentType<Versioned<SuitState>> SUIT = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "suit"),
            builder -> builder.persistent(Versioned.codec(SuitState.VERSION, SuitState.BODY))
                    .copyOnDeath()
                    .initializer(() -> Versioned.of(SuitState.full()))
                    .syncWith(
                            Versioned.streamCodec(SuitState.VERSION, SuitState.STREAM),
                            AttachmentSyncPredicate.targetOnly()));

    /** What a breached suit hurts with. */
    public static final ResourceKey<DamageType> EXPOSURE = ResourceKey.create(
            Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "suit_exposure"));

    private SuitRegistry() {}

    /** Loads the class, which registers the attachment. */
    public static void register() {}
}
