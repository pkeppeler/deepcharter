package io.github.pkeppeler.deepcharter.test.support;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A real {@link ServerPlayer} whose connection is a Netty {@link EmbeddedChannel}. Create one
 * with {@link MockPlayers#join}. Everything here runs on the server thread.
 */
public final class MockPlayer {
    private static final int TELEPORT_IDS = 32;
    private final MinecraftServer server;
    private final ServerPlayer player;
    private final Connection connection;
    private final EmbeddedChannel channel;
    private final BooleanSupplier ownerDone;
    private final List<Component> actionBar = new ArrayList<>();
    private final List<Component> chat = new ArrayList<>();
    private final List<Identifier> sounds = new ArrayList<>();
    private final List<ItemStack> slotUpdates = new ArrayList<>();
    private final List<PlacedSound> soundsAtPlaces = new ArrayList<>();
    private final List<Identifier> soundsStopped = new ArrayList<>();
    private final Map<Integer, List<Identifier>> soundsByEntity = new HashMap<>();
    private final List<Long> soundTicks = new ArrayList<>();
    private final List<Identifier> soundsInTheWorld = new ArrayList<>();
    private final List<CustomPacketPayload> payloads = new ArrayList<>();
    private final List<Packet<?>> packets = new ArrayList<>();
    private final Map<ChunkPos, Integer> chunksSent = new HashMap<>();
    private final Map<ResourceKey<Level>, Map<ChunkPos, Integer>> chunksSentByLevel = new HashMap<>();
    private final List<ChunkPos> forgetsAfterRespawn = new ArrayList<>();
    private ResourceKey<Level> clientLevel;
    private final MockClientLight clientLight = new MockClientLight();
    private final Set<RecipeDisplayId> recipeDisplays = new HashSet<>();
    private final Set<RecipeDisplayId> recipeDisplaysEverSent = new HashSet<>();
    private int recipeToasts;
    private boolean loaded;

    MockPlayer(
            MinecraftServer server,
            ServerPlayer player,
            Connection connection,
            EmbeddedChannel channel,
            BooleanSupplier ownerDone) {
        this.server = server;
        this.player = player;
        this.connection = connection;
        this.channel = channel;
        this.ownerDone = ownerDone;
        this.clientLevel = player.level().dimension();
    }

    /** Every item stack the server has sent this player for a container slot so far, oldest first, as copies. */
    public List<ItemStack> slotUpdates() {
        return List.copyOf(slotUpdates);
    }

    /** Every action-bar message the server has sent this player so far, oldest first. */
    public List<Component> actionBarMessages() {
        return List.copyOf(actionBar);
    }

    /** Every chat message (not action bar) the server has sent this player so far, oldest first. Read on the mock's tick, so it lags by one. */
    public List<Component> chatMessages() {
        return List.copyOf(chat);
    }

    /** The id of every sound the server has played to this player alone (a sound entity packet) so far, oldest first. Read on the mock's tick, so it lags by one. */
    public List<Identifier> soundsHeard() {
        return List.copyOf(sounds);
    }

    /** A sound the server played at a place in the world. */
    public record PlacedSound(Identifier id, Vec3 at) {}

    /**
     * Every sound the server has played to this player at a place in the world (a sound packet) so far, oldest first. Read on the mock's tick,
     * so it lags by one. Tests run side by side in one level, so a sound of another test's block can be here: filter by place.
     */
    public List<PlacedSound> soundsHeardAtPlaces() {
        return List.copyOf(soundsAtPlaces);
    }

    /**
     * The id of every sound the server has told this player's client to stop so far, oldest first. Read on the mock's tick, so it lags by
     * one. A stop names no place, so a test beside other tests asks whether one is among them.
     */
    public List<Identifier> soundsStopped() {
        return List.copyOf(soundsStopped);
    }

    /** The id of every sound that the server played at a place in the world and sent this player (a sound packet) so far, oldest first. Read on the mock's tick, so it lags by one. */
    public List<Identifier> soundsInTheWorld() {
        return List.copyOf(soundsInTheWorld);
    }

    /** As {@link #soundsHeard}, only sounds played at {@code source}, so nearby tests do not hear each other. */
    public List<Identifier> soundsHeardFrom(Entity source) {
        return List.copyOf(soundsByEntity.getOrDefault(source.getId(), List.of()));
    }

    /**
     * The level game time at which the mock read each sound of {@link #soundsHeard()}, in order. The mock reads at
     * END_SERVER_TICK, so a sound sent earlier in that tick carries its play tick.
     */
    public List<Long> soundTicks() {
        return List.copyOf(soundTicks);
    }

    /**
     * Every chunk the server has sent this player so far. The mock acknowledges each chunk batch, so sending is not throttled.
     * Read on the mock's tick, so it lags by one.
     */
    public Set<ChunkPos> chunksSent() {
        return Set.copyOf(chunksSent.keySet());
    }

    /**
     * How many times the server sent this player the chunk while the client was in {@code level}, as the client's level changes with
     * each respawn packet. {@link #timesSent} counts a chunk of every level together. Read on the mock's tick, so it lags by one.
     */
    public int timesSentIn(ResourceKey<Level> level, ChunkPos chunk) {
        return chunksSentByLevel.getOrDefault(level, Map.of()).getOrDefault(chunk, 0);
    }

    /** Every chunk the server sent this player while the client was in {@code level}. Read on the mock's tick, so it lags by one. */
    public Set<ChunkPos> chunksSentIn(ResourceKey<Level> level) {
        return Set.copyOf(chunksSentByLevel.getOrDefault(level, Map.of()).keySet());
    }

    /**
     * The chunks the server told the client to forget after the latest respawn packet: a client in its new level drops each of them
     * (vanilla's {@code dropChunk} runs after the respawn). Empty before any respawn. Read on the mock's tick, so it lags by one.
     */
    public List<ChunkPos> forgetsAfterRespawn() {
        return List.copyOf(forgetsAfterRespawn);
    }

    /** The light this player's client holds, from the chunk and light packets it was sent. Read on the mock's tick, so it lags by one. */
    public MockClientLight clientLight() {
        return clientLight;
    }

    /** How many times the server has sent this player the chunk; a chunk it already has is not sent again. */
    public int timesSent(ChunkPos chunk) {
        return chunksSent.getOrDefault(chunk, 0);
    }

    /**
     * The id of every recipe the server has put in this player's recipe book, as a client would hold them: the add and remove
     * packets applied in order. Read on the mock's tick, so it lags by one.
     */
    public Set<Identifier> recipesHeld() {
        return recipeIds(recipeDisplays);
    }

    /** The id of every recipe the server has sent this player at any time, even one it removed again. Read on the mock's tick, so it lags by one. */
    public Set<Identifier> recipesEverSent() {
        return recipeIds(recipeDisplaysEverSent);
    }

    private Set<Identifier> recipeIds(Set<RecipeDisplayId> displays) {
        Set<Identifier> held = new HashSet<>();
        for (RecipeDisplayId display : displays) {
            RecipeManager.ServerDisplayInfo info = server.getRecipeManager().getRecipeFromDisplay(display);
            if (info != null) {
                held.add(info.parent().id().identifier());
            }
        }
        return held;
    }

    /** How many recipes the server has told this player to announce with a toast so far. Read on the mock's tick, so it lags by one. */
    public int recipeToasts() {
        return recipeToasts;
    }

    /**
     * Every custom payload of {@code type} the server has sent this player so far, oldest first. Read on the mock's tick, so it lags by
     * one. The server sends a payload only to a client that announced its channel: call {@link #announceChannel} first.
     */
    public <T extends CustomPacketPayload> List<T> payloadsOf(Class<T> type) {
        return payloads.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /**
     * Every packet of {@code type} the server has sent this player so far, oldest first, with a bundle's packets taken
     * apart. Read on the mock's tick, so it lags by one.
     */
    private <T extends Packet<?>> List<T> packetsOf(Class<T> type) {
        return packets.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /**
     * Every packet the server has sent this player so far, oldest first, a custom payload as its payload, a bundle
     * taken apart: for a test of the order packets came in. Read on the mock's tick, so it lags by one.
     */
    public List<Object> sequence() {
        List<Object> sequence = new ArrayList<>();
        for (Packet<?> packet : packets) {
            sequence.add(packet instanceof ClientboundCustomPayloadPacket custom ? custom.payload() : packet);
        }
        return sequence;
    }

    /**
     * How many respawn packets the server has sent this player: a swap sends none. Read on the mock's tick, so it lags
     * by one.
     */
    public int respawns() {
        return packetsOf(ClientboundRespawnPacket.class).size();
    }

    /**
     * Tell the server this client can receive {@code type}, as a real client does with Fabric's channel registration, so that
     * {@code ServerPlayNetworking.canSend} is true for the mock. It uses Fabric's impl {@code RegistrationPayload}, because no public
     * API makes {@code canSend} true for a mock: a Fabric bump that breaks it is fixed here.
     */
    public void announceChannel(CustomPacketPayload.Type<?> type) {
        player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
                new RegistrationPayload(RegistrationPayload.REGISTER, List.of(type.id()))));
    }

    /**
     * Sets the view distance the client asks the server for, in chunks, as its options packet would. A mock asks for 2, which is less
     * than the arrival area of a seam: call this before it crosses to see the chunks the server sends around the arrival.
     */
    public void setViewDistance(int chunks) {
        ClientInformation info = player.clientInformation();
        player.updateOptions(new ClientInformation(
                info.language(),
                chunks,
                info.chatVisibility(),
                info.chatColors(),
                info.modelCustomisation(),
                info.mainHand(),
                info.textFilteringEnabled(),
                info.allowsListing(),
                info.particleStatus()));
    }

    /** Sends the server a custom payload as the client would, for a C2S payload the server has registered. */
    public void send(CustomPacketPayload payload) {
        player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(payload));
    }

    public ServerPlayer player() {
        return player;
    }

    /** True while the player is on the server's player list. */
    public boolean isOnline() {
        return server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    /** Set the input the server reads from the player, as a serverbound input packet would. */
    public void setInput(Input input) {
        player.setLastClientInput(input);
        player.setShiftKeyDown(input.shift());
    }

    public void releaseInput() {
        setInput(Input.EMPTY);
    }

    /**
     * Report the client as loaded, so the player can take damage, and confirm the dimension
     * change. A loaded mock re-confirms every dimension change each tick.
     */
    public void markLoaded() {
        loaded = true;
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        confirmDimensionChange();
    }

    /**
     * Confirm a change of dimension, as a real client does. Until then the server treats the
     * player as mid-change and it takes no damage. {@link #teleportTo} calls this; call it
     * yourself after any other change of dimension, such as a portal or a breach crossing.
     */
    public void confirmDimensionChange() {
        player.hasChangedDimension();
    }

    public void teleportTo(ServerLevel level, Vec3 pos, float yRot, float xRot) {
        if (!player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), yRot, xRot, true)) {
            throw new IllegalStateException(
                    "Mock player " + player.getGameProfile().name() + " could not teleport to " + pos + " in "
                            + level.dimension().identifier()
                            + " (it is at " + player.position() + " in "
                            + player.level().dimension().identifier() + ", removed " + player.isRemoved()
                            + ", alive " + player.isAlive() + ", online " + isOnline() + ")");
        }
        confirmDimensionChange();
    }

    /**
     * Answers every teleport the server is waiting on, as a real client does: until then the server drops the player's position and
     * vehicle packets. Mounting a vehicle teleports the rider. The server numbers its teleports from 1 and keeps no list, so this
     * answers each number from 1 to {@link #TELEPORT_IDS}; an answer to a number it is not waiting for is ignored.
     */
    public void acknowledgeTeleports() {
        for (int id = 1; id <= TELEPORT_IDS; id++) {
            player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(
                    id, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        }
    }

    /** Disconnect and remove the player, also one that respawned (#698). Safe to call when it has already left. */
    public void leave() {
        if (connection.isConnected()) {
            connection.disconnect(Component.literal("mock player left"));
            connection.handleDisconnection();
        }
        MockPlayers.forget(this);
    }

    MinecraftServer server() {
        return server;
    }

    private void record(Object outbound) {
        if (outbound instanceof ClientboundBundlePacket bundle) {
            bundle.subPackets().forEach(this::record);
        } else if (outbound instanceof Packet<?> packet) {
            packets.add(packet);
        }
    }

    /**
     * One server tick of the fake network: tick the connection (which runs the packet listener
     * and sends keep-alives), then drain everything the server wrote to the channel, answering
     * each keep-alive as a real client would.
     */
    void tick() {
        if (ownerDone.getAsBoolean()) {
            leave();
            return;
        }
        if (loaded) {
            confirmDimensionChange();
        }
        connection.tick();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                record(outbound);
                if (outbound instanceof ClientboundSystemChatPacket packet) {
                    (packet.overlay() ? actionBar : chat).add(packet.content());
                }
                if (outbound instanceof ClientboundCustomPayloadPacket packet) {
                    payloads.add(packet.payload());
                }
                if (outbound instanceof ClientboundContainerSetSlotPacket packet) {
                    slotUpdates.add(packet.getItem().copy());
                }
                if (outbound instanceof ClientboundSoundEntityPacket packet) {
                    sounds.add(packet.getSound().value().location());
                    soundsByEntity
                            .computeIfAbsent(packet.getId(), id -> new ArrayList<>())
                            .add(packet.getSound().value().location());
                    soundTicks.add(player.level().getGameTime());
                }
                if (outbound instanceof ClientboundSoundPacket packet) {
                    soundsInTheWorld.add(packet.getSound().value().location());
                }
                if (outbound instanceof ClientboundRecipeBookAddPacket packet) {
                    if (packet.replace()) {
                        recipeDisplays.clear();
                    }
                    for (ClientboundRecipeBookAddPacket.Entry entry : packet.entries()) {
                        recipeDisplays.add(entry.contents().id());
                        recipeDisplaysEverSent.add(entry.contents().id());
                        recipeToasts += entry.notification() ? 1 : 0;
                    }
                }
                if (outbound instanceof ClientboundRecipeBookRemovePacket packet) {
                    packet.recipes().forEach(recipeDisplays::remove);
                }
                if (outbound instanceof ClientboundSoundPacket packet) {
                    soundsAtPlaces.add(new PlacedSound(
                            packet.getSound().value().location(),
                            new Vec3(packet.getX(), packet.getY(), packet.getZ())));
                }
                if (outbound instanceof ClientboundStopSoundPacket packet) {
                    soundsStopped.add(packet.getName());
                }
                if (outbound instanceof ClientboundLevelChunkWithLightPacket packet) {
                    chunksSent.merge(new ChunkPos(packet.x(), packet.z()), 1, Integer::sum);
                    chunksSentByLevel
                            .computeIfAbsent(clientLevel, key -> new HashMap<>())
                            .merge(new ChunkPos(packet.x(), packet.z()), 1, Integer::sum);
                    clientLight.apply(
                            server.getTickCount(), "chunk", new ChunkPos(packet.x(), packet.z()), packet.lightData());
                }
                if (outbound instanceof ClientboundRespawnPacket packet) {
                    clientLevel = packet.commonPlayerSpawnInfo().dimension();
                    forgetsAfterRespawn.clear();
                }
                if (outbound instanceof ClientboundForgetLevelChunkPacket packet) {
                    forgetsAfterRespawn.add(packet.pos());
                }
                if (outbound instanceof ClientboundLightUpdatePacket packet) {
                    clientLight.apply(
                            server.getTickCount(), "light", new ChunkPos(packet.x(), packet.z()), packet.lightData());
                }
                // connection.tick() above may have disconnected the player, which nulls its listener
                if (outbound instanceof ClientboundChunkBatchFinishedPacket && player.connection != null) {
                    player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(10f));
                }
                if (outbound instanceof ClientboundKeepAlivePacket keepAlive && player.connection != null) {
                    player.connection.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
    }
}
