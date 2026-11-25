package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A client that walks a mock on foot the way vanilla's local player does vertically: gravity, a collision that clips the fall, an
 * on-ground flag that is the collision below, and the position packet rule (a move, or a reminder every 20 ticks, otherwise a status
 * only packet when the flag changed). {@code phantoms} are shapes the client holds that the server has as air. The server's own
 * player is moved by the packets alone.
 */
public final class FootClient {
    private static final int REMINDER_TICKS = 20;
    private static final double MOVED_SQ = 2.0E-4 * 2.0E-4;
    private static final double JUMP_SPEED = 0.42;

    private final MockPlayer mock;
    private final List<VoxelShape> phantoms;
    private Vec3 pos;
    private double vy;
    private boolean onGround;
    private boolean lastSentGround;
    private Vec3 lastSent;
    private int sinceSent;
    private int packets;
    private long lastTick = -1;

    public FootClient(MockPlayer mock, List<VoxelShape> phantoms) {
        this.mock = mock;
        this.phantoms = phantoms;
        this.pos = mock.player().position();
        this.lastSent = pos;
    }

    public Vec3 position() {
        return pos;
    }

    public boolean onGround() {
        return onGround;
    }

    public int packets() {
        return packets;
    }

    /** One client tick: fall or jump, collide, and send what vanilla would send. */
    public void tick(boolean jump) {
        ServerPlayer player = mock.player();
        long now = player.level().getGameTime();
        if (now == lastTick) {
            return;
        }
        lastTick = now;
        if (jump && onGround) {
            vy = JUMP_SPEED;
        }
        vy = (vy - 0.08) * 0.98;
        AABB box = new AABB(pos.x - 0.3, pos.y, pos.z - 0.3, pos.x + 0.3, pos.y + 1.8, pos.z + 0.3);
        Vec3 wanted = new Vec3(0, vy, 0);
        Vec3 got = Entity.collideBoundingBox((Entity) player, wanted, box, player.level(), phantoms);
        boolean hitY = got.y != wanted.y;
        onGround = hitY && vy < 0;
        if (hitY) {
            vy = 0;
        }
        pos = pos.add(got);
        sinceSent++;
        boolean moved = pos.distanceToSqr(lastSent) > MOVED_SQ || sinceSent >= REMINDER_TICKS;
        if (moved) {
            player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(pos, onGround, false));
            lastSent = pos;
            sinceSent = 0;
            packets++;
        } else if (onGround != lastSentGround) {
            player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.StatusOnly(onGround, false));
            packets++;
        }
        lastSentGround = onGround;
        // The end of the client tick: it is what lets the server take the next position packet
        // (receivedPositionThisTick).
        player.connection.handleClientTickEnd(new ServerboundClientTickEndPacket());
    }
}
