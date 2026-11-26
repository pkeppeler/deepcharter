package io.github.pkeppeler.deepcharter.continuity;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;

/** The Continuity Event's registrations: the per-person record and the payload to the dying client. */
public final class ContinuityRegistry {
    /**
     * Per player, persistent and kept on death: how many Continuity Events the person has had. Not synced: the dying client is sent the
     * number in {@link ContinuityEventPayload}, the one place it is shown.
     */
    public static final AttachmentType<Versioned<ContinuityRecord>> RECORD = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "continuity"),
            builder -> builder.persistent(Versioned.codec(ContinuityRecord.VERSION, ContinuityRecord.BODY))
                    .copyOnDeath()
                    .initializer(() -> Versioned.of(ContinuityRecord.NONE)));

    private ContinuityRegistry() {}

    /** Loads the class, which registers the attachment, and registers the payload. */
    public static void register() {
        ContinuityEventPayload.register();
    }
}
