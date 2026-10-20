package io.github.pkeppeler.deepcharter.test.support;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.phys.Vec3;

/**
 * Joins real {@link ServerPlayer}s to a server with no network client behind them. This mirrors
 * vanilla's {@code GameTestHelper.makeMockServerPlayerInLevel}: a {@code Connection} on an
 * {@link EmbeddedChannel}, added through {@code PlayerList.placeNewPlayer}. Fabric's FakePlayer
 * is not an option, because its {@code startRiding} always returns false.
 *
 * <p>Vanilla's helper is built for short tests. These players live as long as the server does,
 * so each server tick this class also:
 * <ul>
 *   <li>ticks the mock's {@code Connection}, which ticks its packet listener. Vanilla only does
 *       that for connections it accepted itself;</li>
 *   <li>empties the channel's outbound queue, so packets do not pile up in memory;</li>
 *   <li>answers every {@code ClientboundKeepAlivePacket}, because vanilla kicks a player that
 *       has not answered one for 15 seconds.</li>
 * </ul>
 * <p>A mock is loaded by default, so it takes damage like a real player. Damage tests must call
 * {@code setGameMode(SURVIVAL)}, because GameTest players default to creative.
 * {@link #joinUnloaded} gives one that is immune, but only for vanilla's first 60 ticks: to wait safely
 * for longer, stay in creative and switch to survival when the wait is over.
 *
 * <p>A mock joined for a GameTest never outlives it: the end-of-tick sweep removes it once the
 * test is done, whether it passed, failed or timed out. A mock joined without an owner lives
 * until the server stops or {@link MockPlayer#leave} is called.
 *
 * Drive a mock with {@link MockPlayer#setInput}. Call everything on the server thread.
 */
public final class MockPlayers {
    /** Where a helper-joined mock stands, relative to the test structure. */
    public static final Vec3 STRUCTURE_SPAWN = new Vec3(1.5, 1, 1.5);

    private static final List<MockPlayer> ACTIVE = new ArrayList<>();

    static {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (MockPlayer mock : List.copyOf(ACTIVE)) {
                if (mock.server() == server) {
                    mock.tick();
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ACTIVE.removeIf(mock -> mock.server() == server));
    }

    private MockPlayers() {}

    /** Join a mock player owned by a GameTest, inside its structure (a bare join suffocates at the origin); removed when that test ends. */
    public static MockPlayer join(GameTestHelper helper, String name) {
        return joinInStructure(helper, name, UUID.randomUUID(), true);
    }

    /**
     * Join a mock player at {@code spawn} in {@code level}; it stays until the server stops or {@link MockPlayer#leave}
     * is called. The spawn is required because a bare join lands at the origin, inside the rock of the surface (the
     * ground is at y 63), where the mock suffocates within seconds and a dead mock cannot be teleported.
     */
    public static MockPlayer join(MinecraftServer server, String name, ServerLevel level, Vec3 spawn) {
        return join(server, name, level, spawn, 0f);
    }

    /** Join a mock player at {@code spawn} in {@code level}, facing {@code yaw} (a vanilla yaw), as the 4-argument join does otherwise. */
    public static MockPlayer join(MinecraftServer server, String name, ServerLevel level, Vec3 spawn, float yaw) {
        MockPlayer mock = join(server, name, () -> false, true);
        mock.teleportTo(level, spawn, yaw, 0f);
        return mock;
    }

    /**
     * Join a mock player at {@code spawn} in {@code level}, removed once {@code ownerDone} turns true. It is checked
     * every server tick.
     */
    public static MockPlayer join(
            MinecraftServer server, String name, ServerLevel level, Vec3 spawn, BooleanSupplier ownerDone) {
        MockPlayer mock = join(server, name, ownerDone, true);
        mock.teleportTo(level, spawn, 0f, 0f);
        return mock;
    }

    /** Join a mock player owned by a GameTest with the given id, as a player who comes back to the server. */
    public static MockPlayer join(GameTestHelper helper, String name, UUID id) {
        return joinInStructure(helper, name, id, true);
    }

    private static MockPlayer joinInStructure(GameTestHelper helper, String name, UUID id, boolean loaded) {
        GameTestInfo info = testInfo(helper);
        MockPlayer mock = join(helper.getLevel().getServer(), name, id, info::isDone, loaded);
        mock.teleportTo(helper.getLevel(), helper.absoluteVec(STRUCTURE_SPAWN), 0f, 0f);
        return mock;
    }

    private static MockPlayer join(MinecraftServer server, String name, BooleanSupplier ownerDone, boolean loaded) {
        return join(server, name, UUID.randomUUID(), ownerDone, loaded);
    }

    // An unloaded player is still loading to the server, so it takes no damage, kill included.
    private static MockPlayer join(
            MinecraftServer server, String name, UUID id, BooleanSupplier ownerDone, boolean loaded) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("MockPlayers.join must run on the server thread");
        }
        GameProfile profile = new GameProfile(id, name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, server.overworld(), profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        MockPlayer mock = new MockPlayer(server, player, connection, channel, ownerDone);
        if (loaded) {
            mock.markLoaded();
        }
        ACTIVE.add(mock);
        return mock;
    }

    /**
     * Join a mock player owned by a GameTest that has not reported itself loaded, so it takes no damage.
     * Vanilla treats any player as loaded after 60 ticks, so this only covers a short setup.
     */
    public static MockPlayer joinUnloaded(GameTestHelper helper, String name) {
        return joinInStructure(helper, name, UUID.randomUUID(), false);
    }

    // GameTestHelper has no public accessor for its test, and this is the only way to know when it ends.
    private static GameTestInfo testInfo(GameTestHelper helper) {
        try {
            Field field = GameTestHelper.class.getDeclaredField("testInfo");
            field.setAccessible(true);
            return (GameTestInfo) field.get(helper);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Cannot read GameTestHelper.testInfo, likely a mapping change in Minecraft or Fabric", e);
        }
    }

    static void forget(MockPlayer mock) {
        ACTIVE.remove(mock);
    }
}
