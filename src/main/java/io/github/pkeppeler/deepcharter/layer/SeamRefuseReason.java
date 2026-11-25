package io.github.pkeppeler.deepcharter.layer;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;

/**
 * Why a client refuses a seam (#1229, ADR 0089). The wire id is the position here, and the order is pinned by
 * {@code SeamSwapTest}: a reordering would change what an old client means.
 */
public enum SeamRefuseReason {
    /** The held level could not be built from the prepare. */
    BUILD_FAILED,
    /** The held level was released (a discard, a respawn elsewhere) after it was accepted. */
    RELEASED,
    /** The client swapped no level though the server did: the swap's checks failed there. */
    SWAP_FAILED;

    /** An id out of range decodes as the first reason, a refusal that costs the client nothing, and not as an error. */
    public static final StreamCodec<ByteBuf, SeamRefuseReason> CODEC = ByteBufCodecs.idMapper(
            ByIdMap.continuous(SeamRefuseReason::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO),
            SeamRefuseReason::ordinal);
}
