package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.google.gson.JsonObject;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.handbook.HandbookRegistry;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreTuning;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for #853: carried ore shows its weight. The Overburdened status mirrors {@code OreEncumbrance}'s speed modifier
 * and never slows a player itself, every ore item says it is heavy, and a player's first ore is explained once.
 */
public class OverburdenedTest {
    private static final int MAX_TICKS = 1200;
    private static final String LOAD = "ore_load";
    private static final String FIRST_PICKUP_KEY = "deepcharter.ore.first_pickup";
    private static final String HEAVY_KEY = "deepcharter.ore.tooltip.heavy";
    private static final String HAUL_KEY = "deepcharter.ore.tooltip.haul";
    private static final int SETTLE_TICKS = 10;

    /** One level for each unit of ore carried (an Einsteinium is four), counted from 1; the amplifier counts from 0. */
    private static int amplifierOf(OreType type) {
        return Math.round(type.mass() / OreTuning.DEFAULT.massScale()) - 1;
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theStatusAppearsAndGoesWithTheSlowdownModifier(GameTestHelper helper) {
        MockPlayer walker = MockPlayers.join(helper, "burden-walker");
        ServerPlayer player = walker.player();
        player.getInventory().add(OreRegistry.stack(OreType.EINSTEINIUM));
        boolean[] loaded = {false};
        TestTicks.each(helper, () -> {
            AttributeModifier modifier = loadModifier(player);
            MobEffectInstance effect = player.getEffect(OreRegistry.OVERBURDENED);
            if ((effect != null) != (modifier != null)) {
                walker.leave();
                throw failure(
                        helper,
                        "the status and the speed modifier must come and go together, status %s, modifier %s",
                        effect,
                        modifier);
            }
            if (!loaded[0]) {
                if (effect == null) {
                    return;
                }
                if (effect.getAmplifier() != amplifierOf(OreType.EINSTEINIUM)
                        || !effect.isInfiniteDuration()
                        || effect.isVisible()
                        || !effect.showIcon()) {
                    walker.leave();
                    throw failure(
                            helper,
                            "carrying Einsteinium should show an endless, particle-free status of amplifier %s, got %s",
                            amplifierOf(OreType.EINSTEINIUM),
                            effect);
                }
                loaded[0] = true;
                player.getInventory().clearContent();
                return;
            }
            if (effect == null) {
                walker.leave();
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theStatusLevelFollowsTheOreCarriedAndStopsAtTheCap(GameTestHelper helper) {
        MockPlayer walker = MockPlayers.join(helper, "burden-levels");
        ServerPlayer player = walker.player();
        player.getInventory().add(OreRegistry.stack(OreType.IRONIUM));
        int[] stage = {0};
        TestTicks.each(helper, () -> {
            MobEffectInstance effect = player.getEffect(OreRegistry.OVERBURDENED);
            if (effect == null) {
                return;
            }
            int expected = stage[0] == 0 ? amplifierOf(OreType.IRONIUM) : capAmplifier();
            if (effect.getAmplifier() != expected) {
                if (stage[0] == 1 && effect.getAmplifier() < expected) {
                    return;
                }
                walker.leave();
                throw failure(
                        helper, "stage %s should show amplifier %s, got %s", stage[0], expected, effect.getAmplifier());
            }
            if (stage[0] == 0) {
                stage[0] = 1;
                for (int i = 0; i < 6; i++) {
                    player.getInventory().add(OreRegistry.stack(OreType.EINSTEINIUM));
                }
                return;
            }
            walker.leave();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theStatusAddsNoSlowdownOfItsOwn(GameTestHelper helper) {
        MockPlayer walker = MockPlayers.join(helper, "burden-once");
        ServerPlayer player = walker.player();
        double bare = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
        player.getInventory().add(OreRegistry.stack(OreType.EINSTEINIUM));
        double expected = bare * (1 - OreType.EINSTEINIUM.mass() * OreTuning.DEFAULT.slowdownPerMass());
        TestTicks.each(helper, () -> {
            if (player.getEffect(OreRegistry.OVERBURDENED) == null) {
                return;
            }
            AttributeInstance speed = Objects.requireNonNull(
                    player.getAttribute(Attributes.MOVEMENT_SPEED), "a player has no movement speed attribute");
            Set<String> modifiers =
                    speed.getModifiers().stream().map(m -> m.id().getPath()).collect(Collectors.toSet());
            walker.leave();
            if (!modifiers.equals(Set.of(LOAD)) || Math.abs(speed.getValue() - expected) > 1e-6) {
                throw failure(
                        helper,
                        "with the status shown the speed should be %s from the %s modifier alone, got %s from %s",
                        expected,
                        LOAD,
                        speed.getValue(),
                        modifiers);
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theStatusIsHiddenWhileRidingAPodAndReturnsOnFoot(GameTestHelper helper) {
        MockPlayer walker = MockPlayers.join(helper, "burden-rider");
        ServerPlayer player = walker.player();
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        player.getInventory().add(OreRegistry.stack(OreType.IRONIUM));
        int[] stage = {0};
        int[] settled = {0};
        TestTicks.each(helper, () -> {
            boolean shown = player.getEffect(OreRegistry.OVERBURDENED) != null;
            switch (stage[0]) {
                case 0 -> {
                    if (shown) {
                        player.startRiding(pod, true, false);
                        stage[0] = 1;
                    }
                }
                case 1 -> {
                    if (!shown && ++settled[0] >= SETTLE_TICKS) {
                        player.stopRiding();
                        stage[0] = 2;
                    } else if (shown && settled[0] > 0) {
                        walker.leave();
                        throw failure(helper, "the status came back while riding a pod");
                    }
                }
                default -> {
                    if (shown) {
                        pod.discard();
                        walker.leave();
                        helper.succeed();
                    }
                }
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void clearingEffectsRemovesTheStatusAndItReturnsWhileOreIsCarried(GameTestHelper helper) {
        MockPlayer walker = MockPlayers.join(helper, "burden-milk");
        ServerPlayer player = walker.player();
        player.getInventory().add(OreRegistry.stack(OreType.GOLDIUM));
        boolean[] cleared = {false};
        TestTicks.each(helper, () -> {
            if (player.getEffect(OreRegistry.OVERBURDENED) == null) {
                return;
            }
            if (!cleared[0]) {
                cleared[0] = true;
                player.removeAllEffects();
                if (player.getEffect(OreRegistry.OVERBURDENED) != null) {
                    walker.leave();
                    throw failure(helper, "removeAllEffects should remove the status");
                }
                return;
            }
            if (loadModifier(player) == null) {
                walker.leave();
                throw failure(helper, "the status came back without the slowdown modifier");
            }
            walker.leave();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theFirstOreExplainsItselfOnceAndASecondPickupSaysNothing(GameTestHelper helper) {
        MockPlayer walker = MockPlayers.join(helper, "burden-first");
        ServerPlayer player = walker.player();
        int[] stage = {0};
        int[] settled = {0};
        TestTicks.each(helper, () -> {
            switch (stage[0]) {
                case 0 -> {
                    if (++settled[0] == SETTLE_TICKS) {
                        requireLines(helper, walker, 0, "before any ore");
                        player.getInventory().add(OreRegistry.stack(OreType.IRONIUM));
                        stage[0] = 1;
                    }
                }
                case 1 -> {
                    if (player.getEffect(OreRegistry.OVERBURDENED) != null) {
                        player.getInventory().clearContent();
                        settled[0] = 0;
                        stage[0] = 2;
                    }
                }
                case 2 -> {
                    if (player.getEffect(OreRegistry.OVERBURDENED) == null && ++settled[0] >= SETTLE_TICKS) {
                        requireLines(helper, walker, 1, "after the first ore");
                        if (!ReadMarks.isRead(player, OreEncumbrance.FIRST_PICKUP_MARK)) {
                            walker.leave();
                            throw failure(helper, "the first pickup should be kept as a read mark on the player");
                        }
                        player.getInventory().add(OreRegistry.stack(OreType.EINSTEINIUM));
                        settled[0] = 0;
                        stage[0] = 3;
                    }
                }
                default -> {
                    if (player.getEffect(OreRegistry.OVERBURDENED) != null && ++settled[0] >= SETTLE_TICKS) {
                        requireLines(helper, walker, 1, "after a second pickup");
                        walker.leave();
                        helper.succeed();
                    }
                }
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void unreadableReadMarksNeitherThrowNorStopTheStatus(GameTestHelper helper) {
        MockPlayer walker = MockPlayers.join(helper, "burden-unreadable");
        ServerPlayer player = walker.player();
        UnreadableChecks.makeUnreadable(player, HandbookRegistry.READ_MARKS);
        player.getInventory().add(OreRegistry.stack(OreType.SILVERIUM));
        int[] shown = {0};
        TestTicks.each(helper, () -> {
            if (player.getEffect(OreRegistry.OVERBURDENED) != null && ++shown[0] >= SETTLE_TICKS) {
                requireLines(helper, walker, 0, "with marks that cannot be read");
                if (!(player.getAttached(HandbookRegistry.READ_MARKS) instanceof Versioned.Unreadable<?>)) {
                    walker.leave();
                    throw failure(helper, "the unreadable marks must be left as they were");
                }
                walker.leave();
                helper.succeed();
            }
        });
    }

    @GameTest
    public void everyItemOreEncumbranceCountsSaysItIsHeavyAndNoOtherItemDoes(GameTestHelper helper) {
        MockPlayer holder = MockPlayers.join(helper, "burden-tooltip");
        ServerPlayer player = holder.player();
        try {
            int heavy = 0;
            for (Item item : BuiltInRegistries.ITEM) {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                if (!id.getNamespace().equals("deepcharter")) {
                    continue;
                }
                player.getInventory().clearContent();
                player.getInventory().setItem(0, new ItemStack(item));
                boolean counted = OreEncumbrance.carriedMass(player) > 0;
                List<Component> lines = new ItemStack(item)
                        .getTooltipLines(Item.TooltipContext.of(helper.getLevel()), null, TooltipFlag.NORMAL);
                boolean saysHeavy = has(lines, HEAVY_KEY);
                // Only ore is hauled in the bay: the solid fuels (#1280) are counted and heavy, and ride in the pack.
                boolean ore = OreRegistry.typeOf(new ItemStack(item)).isPresent();
                if (counted != saysHeavy || ore != has(lines, HAUL_KEY)) {
                    throw failure(
                            helper,
                            "%s: OreEncumbrance counts it %s, its tooltip has the heavy and haul lines %s and %s",
                            id,
                            counted,
                            saysHeavy,
                            has(lines, HAUL_KEY));
                }
                if (ore) {
                    heavy++;
                }
            }
            // The vanilla fuels carry the weight too: counted implies heavy, whoever's item it is.
            for (Item fuel : List.of(Items.COAL, Items.CHARCOAL)) {
                player.getInventory().clearContent();
                player.getInventory().setItem(0, new ItemStack(fuel));
                List<Component> lines = new ItemStack(fuel)
                        .getTooltipLines(Item.TooltipContext.of(helper.getLevel()), null, TooltipFlag.NORMAL);
                if (!(OreEncumbrance.carriedMass(player) > 0) || !has(lines, HEAVY_KEY) || has(lines, HAUL_KEY)) {
                    throw failure(
                            helper,
                            "%s should be counted and heavy, and ride in the pack, not the bay (counted %s)",
                            fuel,
                            OreEncumbrance.carriedMass(player));
                }
            }
            if (heavy != OreType.values().length) {
                throw failure(
                        helper, "every ore type is one heavy item, found %s of %s", heavy, OreType.values().length);
            }
            helper.succeed();
        } finally {
            holder.leave();
        }
    }

    @GameTest
    public void theNewLinesAreInTheLanguageFile(GameTestHelper helper) {
        JsonObject lang = HandbookChaptersOneToFiveTest.lang();
        for (String key : List.of(FIRST_PICKUP_KEY, HEAVY_KEY, HAUL_KEY, "effect.deepcharter.overburdened")) {
            if (!lang.has(key) || lang.get(key).getAsString().isBlank()) {
                throw failure(helper, "the language file has no line for %s", key);
            }
        }
        helper.succeed();
    }

    private static int capAmplifier() {
        return Math.round((float) (OreTuning.DEFAULT.maxSlowdown()
                        / (OreTuning.DEFAULT.slowdownPerMass() * OreTuning.DEFAULT.massScale())))
                - 1;
    }

    private static boolean has(List<Component> lines, String key) {
        return lines.stream()
                .anyMatch(line -> line.getContents() instanceof TranslatableContents translatable
                        && translatable.getKey().equals(key));
    }

    private static void requireLines(GameTestHelper helper, MockPlayer walker, int expected, String when) {
        long lines = walker.chatMessages().stream()
                .filter(line -> line.getContents() instanceof TranslatableContents translatable
                        && translatable.getKey().equals(FIRST_PICKUP_KEY))
                .count();
        if (lines != expected) {
            walker.leave();
            throw failure(
                    helper, "%s the first-ore line should have been sent %s time(s), was %s", when, expected, lines);
        }
    }

    private static @Nullable AttributeModifier loadModifier(ServerPlayer player) {
        return Objects.requireNonNull(
                        player.getAttribute(Attributes.MOVEMENT_SPEED), "a player has no movement speed attribute")
                .getModifiers()
                .stream()
                .filter(modifier -> modifier.id().getPath().equals(LOAD))
                .findFirst()
                .orElse(null);
    }
}
