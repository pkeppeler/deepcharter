package io.github.pkeppeler.deepcharter.handbook;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.Holder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;

/**
 * Serverbound: the sender has viewed a chapter in the handbook screen. The server marks the chapter read for the sender, and only
 * the sender. Nothing the client says is trusted: the chapter must exist, and the sender's charter must be allowed to read all of
 * it ({@link HandbookVisibility#FULL}), so a modified client cannot mark a classified chapter. A request that fails a check is
 * dropped without a reply. A player whose saved read marks cannot be read is logged once and skipped.
 */
public record HandbookReadPayload(Identifier chapter) implements CustomPacketPayload {
	public static final Type<HandbookReadPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook_read"));
	public static final StreamCodec<RegistryFriendlyByteBuf, HandbookReadPayload> CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, HandbookReadPayload::chapter,
			HandbookReadPayload::new);

	/** Players already reported as unreadable, so that a repeated request logs nothing more. */
	private static final Set<UUID> REPORTED = ConcurrentHashMap.newKeySet();

	/** Registers the payload type and its receiver. */
	static void register() {
		PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> REPORTED.clear());
		ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> handle(context.server(), context.player(), payload.chapter()));
	}

	/** Marks {@code chapter} read for {@code player} if the request passes every check. Returns whether the chapter is now marked. */
	public static boolean handle(MinecraftServer server, ServerPlayer player, Identifier chapter) {
		if (player.getAttachedOrCreate(HandbookRegistry.READ_MARKS) instanceof Versioned.Unreadable<ReadMarks> unreadable) {
			if (REPORTED.add(player.getUUID())) {
				DeepCharter.LOGGER.error("Not marking {} read for {}: their read marks have saved version {} that this build cannot read",
						chapter, player.getGameProfile().name(), unreadable.version());
			}
			return false;
		}
		if (ReadMarks.isRead(player, chapter)) {
			return true;
		}
		if (!viewableFor(server, player.getUUID(), chapter)) {
			return false;
		}
		ReadMarks.mark(player, chapter);
		return true;
	}

	/** Whether {@code chapter} exists and the charter of {@code player} may read all of it. */
	public static boolean viewableFor(MinecraftServer server, UUID player, Identifier chapter) {
		List<Holder.Reference<HandbookChapter>> chapters = HandbookChapters.all(server.registryAccess());
		return viewable(chapters.stream().map(entry -> entry.key().identifier()).toList(),
				chapters.stream().map(entry -> entry.value().directives().stream().map(HandbookChapter.Entry::id).toList()).toList(),
				HandbookProgress.completedFor(server, player), chapter);
	}

	/**
	 * Whether {@code chapter} is one of {@code chapterIds} and is {@link HandbookVisibility#FULL} for a charter that completed
	 * {@code completed}. Pure.
	 *
	 * @param chapterIds the chapter ids in handbook order
	 * @param directives the directive ids of each chapter, in the same order as {@code chapterIds}
	 */
	public static boolean viewable(List<Identifier> chapterIds, List<List<Identifier>> directives, Set<Identifier> completed, Identifier chapter) {
		int index = chapterIds.indexOf(chapter);
		return index >= 0 && HandbookVisibility.of(directives, completed).get(index) == HandbookVisibility.FULL;
	}

	@Override
	public Type<HandbookReadPayload> type() {
		return TYPE;
	}
}
