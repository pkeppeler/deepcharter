package io.github.pkeppeler.deepcharter.repair;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;

/**
 * The thrown kit (#985, CREATURES section 8): the operator's flares, decoys and charges, by hand, on foot. A person who is not seated throws the item in
 * their hand along the way they look; what it does when it lands is {@link ThrownKitEntity}'s. The three are bought at the repair station like the other
 * consumables ({@link Consumable}), so a fight costs money (pillar 1). A throw from any seat is refused and keeps the item: the pod's charge is
 * {@link Consumable#BLASTING_CHARGE}.
 */
public final class ThrownKit {
    private ThrownKit() {}

    /** Server only: throws {@code item} for {@code player}. Empty when it was thrown (the caller uses it up), or the reason it was not. */
    public static Optional<Component> throwFrom(ServerPlayer player, Consumable item, ThrownKitTuning tuning) {
        if (player.isPassenger()) {
            return Consumables.refusal("throw_from_seat");
        }
        ServerLevel level = player.level();
        Projectile.spawnProjectileFromRotation(
                (thrownIn, owner, stack) -> new ThrownKitEntity(thrownIn, owner, stack, item, tuning),
                level,
                new ItemStack(RepairRegistry.item(item)),
                player,
                0f,
                tuning.throwSpeed(),
                0f);
        level.playSound(
                null, player.getX(), player.getY(), player.getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS);
        return Optional.empty();
    }
}
