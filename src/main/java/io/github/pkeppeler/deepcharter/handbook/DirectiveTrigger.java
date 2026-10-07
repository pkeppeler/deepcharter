package io.github.pkeppeler.deepcharter.handbook;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/**
 * The criterion {@code deepcharter:directive}: met when our own code calls {@link Directives#fire} with the directive id the
 * criterion names. An advancement that lists it can mix it with vanilla criteria, so one directive can complete from either.
 */
public final class DirectiveTrigger extends SimpleCriterionTrigger<DirectiveTrigger.Instance> {
	@Override
	public Codec<Instance> codec() {
		return Instance.CODEC;
	}

	/** Meets every criterion of {@code player} that names {@code directive}. */
	public void trigger(ServerPlayer player, Identifier directive) {
		trigger(player, instance -> instance.directive().equals(directive));
	}

	public record Instance(Optional<Holder<LootItemCondition>> player, Identifier directive) implements SimpleInstance {
		public static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				LootItemCondition.CODEC.optionalFieldOf("player").forGetter(Instance::player),
				Identifier.CODEC.fieldOf("directive").forGetter(Instance::directive)).apply(instance, Instance::new));
	}
}
