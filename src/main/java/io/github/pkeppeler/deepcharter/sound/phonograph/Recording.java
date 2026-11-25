package io.github.pkeppeler.deepcharter.sound.phonograph;

import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

/**
 * What a wax cylinder holds: a phonautogram of 1857 to 1860 played back by First Sounds, or one of the later recordings of 1881 to 1888
 * (credits in {@code CREDITS.md}, the audio made by {@code tools/sounds/first_sounds.py}). The saved and synced form is the name, which is
 * permanent.
 *
 * <p>"Au clair de la lune" is never a cylinder: it is the wreck's song and a warning in the dark (#861), and keeps its meaning by
 * staying out of this set. A processed variant of it, when #861 adds one, is its own sound event and not a member here.
 */
public enum Recording implements StringRepresentable, TooltipProvider {
    GAMME_DE_LA_VOIX("gamme_de_la_voix", 1860, DeepSound.PHONOGRAPH_GAMME_DE_LA_VOIX, 319),
    JEUNE_JOUVENCELLE("jeune_jouvencelle", 1857, DeepSound.PHONOGRAPH_JEUNE_JOUVENCELLE, 320),
    OTHELLO("othello", 1860, DeepSound.PHONOGRAPH_OTHELLO, 356),
    VOLTA_COUNTING("volta_counting", 1881, DeepSound.PHONOGRAPH_VOLTA_COUNTING, 218),
    VOLTA_HAMLET("volta_hamlet", 1884, DeepSound.PHONOGRAPH_VOLTA_HAMLET, 343),
    SULLIVAN_TOAST("sullivan_toast", 1888, DeepSound.PHONOGRAPH_SULLIVAN_TOAST, 487),
    EDISON_AROUND_THE_WORLD("edison_around_the_world", 1888, DeepSound.PHONOGRAPH_EDISON_AROUND_THE_WORLD, 491),
    LOST_CHORD("lost_chord", 1888, DeepSound.PHONOGRAPH_LOST_CHORD, 580),
    EDISON_DOLL("edison_doll", 1888, DeepSound.PHONOGRAPH_EDISON_DOLL, 261),
    VOLTA_NURSERY_RHYME("volta_nursery_rhyme", 1885, DeepSound.PHONOGRAPH_VOLTA_NURSERY_RHYME, 480);

    public static final StringRepresentable.EnumCodec<Recording> CODEC =
            StringRepresentable.fromEnum(Recording::values);
    public static final StreamCodec<ByteBuf, Recording> STREAM_CODEC = ByteBufCodecs.STRING_UTF8.map(
            name -> Objects.requireNonNull(CODEC.byName(name), () -> "no recording named " + name),
            Recording::getSerializedName);

    /**
     * What the colony phonograph holds, in the order it plays them, wrapping round: the first three recordings, then the talking doll and
     * the nursery rhyme, which are the colony's alone (LORE guardrail 6: a child's rhyme is for the bar, never the deep, so they are in no
     * loot table and no ambience pool). The other later ones (#946) are only found, in wreck lockers and salvage crates.
     */
    public static final List<Recording> COLONY_RACK =
            List.of(GAMME_DE_LA_VOIX, JEUNE_JOUVENCELLE, OTHELLO, EDISON_DOLL, VOLTA_NURSERY_RHYME);

    private final String id;
    private final int year;
    private final DeepSound sound;
    private final int lengthTicks;

    /**
     * @param lengthTicks the length of the OGG in ticks, rounded up: how long a playback lasts. The length in
     *                    {@code tools/sounds/first_sounds.json} is the source of truth; {@code PhonographTest} compares the two.
     */
    Recording(String id, int year, DeepSound sound, int lengthTicks) {
        this.id = id;
        this.year = year;
        this.sound = sound;
        this.lengthTicks = lengthTicks;
    }

    public int year() {
        return year;
    }

    public DeepSound sound() {
        return sound;
    }

    public int lengthTicks() {
        return lengthTicks;
    }

    /** The title and the year and nothing else, as on the label of a cylinder. */
    public Component label() {
        return Component.translatable(
                "deepcharter.phonograph.label", Component.translatable("deepcharter.recording." + id), year());
    }

    /** The recording a cylinder holds: its component, which the item's default makes always present. */
    public static Recording of(ItemStack cylinder) {
        return Objects.requireNonNull(
                cylinder.get(PhonographRegistry.RECORDING), () -> "no recording on " + cylinder.getItem());
    }

    @Override
    public void addToTooltip(
            Item.TooltipContext context,
            Consumer<Component> builder,
            TooltipFlag flag,
            DataComponentGetter components) {
        builder.accept(label());
    }

    @Override
    public String getSerializedName() {
        return id;
    }
}
