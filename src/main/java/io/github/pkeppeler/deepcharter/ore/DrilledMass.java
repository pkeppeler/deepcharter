package io.github.pkeppeler.deepcharter.ore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * The mass one ore item was drilled at, when it is not its {@link OreType#mass}: a component set on the stack, so the
 * mass goes wherever the ore goes, the hold, a pack or the ground, and {@link OreEncumbrance#massOf} reads it there.
 *
 * <p>It is saved with the stack as {@code deepcharter:drilled_mass}, versioned ({@value #VERSION}) through {@link
 * Versioned}: a version this build cannot read is kept as it was read, and the ore weighs its own mass meanwhile.
 *
 * @param mass the mass of one item, not negative
 */
public record DrilledMass(float mass) {
    public static final int VERSION = 1;

    public static final MapCodec<DrilledMass> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.floatRange(0f, Float.MAX_VALUE).fieldOf("mass").forGetter(DrilledMass::mass))
            .apply(instance, DrilledMass::new));
    public static final StreamCodec<ByteBuf, DrilledMass> STREAM_CODEC =
            ByteBufCodecs.FLOAT.map(DrilledMass::new, DrilledMass::mass);

    /**
     * {@code ore} itself, weighing {@code total} for its whole stack: marked with the mass of one item when that is not
     * the ore's own, and left unmarked when it is, so ore drilled at its own mass is the plain item.
     */
    public static ItemStack at(ItemStack ore, float total) {
        OreType type = OreRegistry.typeOf(ore)
                .orElseThrow(() -> new IllegalArgumentException("only ore has a drilled mass, not " + ore));
        float each = total / ore.getCount();
        if (each == type.mass()) {
            ore.remove(OreRegistry.DRILLED_MASS);
        } else {
            ore.set(OreRegistry.DRILLED_MASS, Versioned.of(new DrilledMass(each)));
        }
        return ore;
    }

    /** The mass of one item of {@code type} in {@code stack}: its drilled mass when it has one this build reads. */
    static float each(ItemStack stack, OreType type) {
        return stack.get(OreRegistry.DRILLED_MASS) instanceof Versioned.Readable<DrilledMass> readable
                ? readable.value().mass()
                : type.mass();
    }
}
