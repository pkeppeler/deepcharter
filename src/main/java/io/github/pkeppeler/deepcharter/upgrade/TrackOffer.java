package io.github.pkeppeler.deepcharter.upgrade;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * What the upgrade terminal offers a parked pod on one track (#1440): what is fitted, the tiers it sells, and what
 * locks them. The crafted tier 1 is never offered (ADR 0048, ADR 0053). The server decides every purchase ({@link
 * UpgradeTerminal#buy}); this mirrors its rules so the screen shows only what it would sell.
 *
 * @param track the track
 * @param slot  the pod's part on it, as the view carries it
 * @param cap   the best tier the pod's chassis works a part at
 */
public record TrackOffer(ComponentTrack track, UpgradeView.Slot slot, int cap) {
    /** Why the track offers nothing to buy yet. */
    public enum Lock {
        /** Not locked: the tiers above what is fitted are for sale. */
        NONE,
        /** The tiers above the crafted one are sold only over it ({@link ComponentTrack#needsFirstTier}). */
        FIT_FIRST,
        /** The track's one tier is crafted, so there is nothing to buy. */
        CRAFT_ONLY
    }

    /** The offer of {@code track} to {@code pod}. */
    public static TrackOffer of(UpgradeView.Pod pod, ComponentTrack track) {
        UpgradeView.Slot slot = pod.slots().stream()
                .filter(held -> held.track() == track)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the view has no slot for " + track));
        return new TrackOffer(track, slot, pod.cap());
    }

    /** True when the pod has a part of the track that counts: a void part does nothing, so it unlocks nothing. */
    public boolean fitted() {
        return slot.effective() > 0;
    }

    public Lock lock() {
        if (fitted()) {
            return Lock.NONE;
        }
        if (track.maxTier() == 1) {
            return Lock.CRAFT_ONLY;
        }
        return track.needsFirstTier() ? Lock.FIT_FIRST : Lock.NONE;
    }

    /**
     * The tiers for sale, lowest first: those above the part fitted, never the crafted tier 1, and none while locked.
     */
    public List<Integer> forSale() {
        if (lock() != Lock.NONE) {
            return List.of();
        }
        int above = fitted() ? slot.installed() : 0;
        return IntStream.rangeClosed(Math.max(2, above + 1), track.maxTier())
                .boxed()
                .toList();
    }

    /**
     * The next purchase: the lowest tier for sale, or tier 2 while it waits for the crafted tier; empty when nothing is
     * left to buy.
     */
    public Optional<Integer> next() {
        return lock() == Lock.FIT_FIRST ? Optional.of(2) : forSale().stream().findFirst();
    }

    /** What the pod has on the track: a tier, the stock part, or nothing. */
    public Component fittedLine() {
        if (slot.installed() == 0) {
            return Component.translatable(
                    UpgradeTuning.DEFAULT.value(track, 0) > 0f
                            ? "screen.deepcharter.upgrade.fitted.stock"
                            : "screen.deepcharter.upgrade.fitted.none");
        }
        MutableComponent line = Component.translatable("screen.deepcharter.upgrade.fitted", slot.installed())
                .append(capped(slot.installed()));
        return fitted() ? line : line.append(Component.translatable("screen.deepcharter.upgrade.void"));
    }

    /**
     * What the next purchase does, in numbers ({@link UpgradeStat#gains}), against what the pod works at now; that it
     * is no better in this pod when the cap holds it back; or that the top tier is fitted. Empty while the track is
     * locked.
     */
    public Optional<Component> nextLine() {
        if (lock() != Lock.NONE) {
            return Optional.empty();
        }
        Optional<Integer> next = next();
        if (next.isEmpty()) {
            return Optional.of(Component.translatable("screen.deepcharter.upgrade.top"));
        }
        int tier = next.get();
        int works = Math.min(tier, cap);
        if (works <= slot.effective()) {
            return Optional.of(Component.translatable("screen.deepcharter.upgrade.next.capped", tier, cap));
        }
        return Optional.of(Component.translatable(
                        "screen.deepcharter.upgrade.next", tier, UpgradeStat.gains(track, slot.effective(), works))
                .append(capped(tier)));
    }

    /** What unlocks the track, naming its crafted unit; empty when it is not locked. */
    public Optional<Component> lockLine() {
        String key = switch (lock()) {
            case NONE -> null;
            case FIT_FIRST -> "screen.deepcharter.upgrade.locked.fit_first";
            case CRAFT_ONLY -> "screen.deepcharter.upgrade.locked.craft_only";
        };
        if (key == null) {
            return Optional.empty();
        }
        String unit = Component.translatable(PartUnits.unit(track).getDescriptionId())
                .getString()
                .toUpperCase(Locale.ROOT);
        return Optional.of(Component.translatable(key, unit));
    }

    /** The "works as tier N" note for a part of {@code tier}, or nothing when the chassis works it in full. */
    public Component capped(int tier) {
        return tier > cap ? Component.translatable("screen.deepcharter.upgrade.capped", cap) : Component.empty();
    }
}
