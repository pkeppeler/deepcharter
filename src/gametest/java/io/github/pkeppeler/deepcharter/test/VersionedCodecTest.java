package io.github.pkeppeler.deepcharter.test;

import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.attachment.Versioned.Readable;
import io.github.pkeppeler.deepcharter.attachment.Versioned.Unreadable;

/** {@link Versioned#codec(int, MapCodec, Map)} on its own, with test-only record types. */
public class VersionedCodecTest {
	private static final int CURRENT = 2;
	private static final int OLD = 1;

	/** Version 2 has a name and a size. Version 1 had only a label, which became the name. */
	private record Thing(String name, int size) {
		static final MapCodec<Thing> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("name").forGetter(Thing::name),
				Codec.INT.fieldOf("size").forGetter(Thing::size)).apply(instance, Thing::new));
		static final MapCodec<Thing> V1_BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("label").forGetter(Thing::name)).apply(instance, label -> new Thing(label, 1)));
	}

	private static final Codec<Versioned<Thing>> WITH_PREVIOUS = Versioned.codec(CURRENT, Thing.BODY, Map.of(OLD, Thing.V1_BODY));

	private static Versioned<Thing> decode(Codec<Versioned<Thing>> codec, Tag tag) {
		return codec.parse(NbtOps.INSTANCE, tag).getOrThrow();
	}

	private static Tag encode(Codec<Versioned<Thing>> codec, Versioned<Thing> value) {
		return codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow();
	}

	private static CompoundTag compound(Integer version, String key, String value) {
		CompoundTag tag = new CompoundTag();
		if (version != null) {
			tag.putInt(Versioned.VERSION_KEY, version);
		}
		tag.putString(key, value);
		return tag;
	}

	/** A body the current codec parses, so only its version can make it unreadable. */
	private static CompoundTag currentShaped(Integer version) {
		CompoundTag tag = compound(version, "name", "shaped");
		tag.putInt("size", 4);
		return tag;
	}

	private static void expectUnreadable(GameTestHelper helper, Codec<Versioned<Thing>> codec, CompoundTag saved, String what) {
		Versioned<Thing> decoded;
		try {
			decoded = decode(codec, saved);
		} catch (RuntimeException e) {
			throw helper.assertionException(what + " must not throw, got " + e);
		}
		if (!(decoded instanceof Unreadable<Thing> unreadable) || !saved.equals(unreadable.raw())) {
			throw helper.assertionException(what + " must be Unreadable with the raw data kept, got " + decoded);
		}
		if (!saved.equals(encode(codec, decoded))) {
			throw helper.assertionException(what + " must be written back unchanged");
		}
	}

	@GameTest
	public void aPreviousVersionDecodesThroughItsOwnCodecAndWritesAtTheCurrentVersion(GameTestHelper helper) {
		Versioned<Thing> decoded = decode(WITH_PREVIOUS, compound(OLD, "label", "old"));
		if (!(decoded instanceof Readable<Thing> readable) || !new Thing("old", 1).equals(readable.value())) {
			throw helper.assertionException("a version 1 body must decode through the version 1 codec, got " + decoded);
		}
		Tag written = encode(WITH_PREVIOUS, decoded);
		if (!(written instanceof CompoundTag compound)
				|| compound.getIntOr(Versioned.VERSION_KEY, -1) != CURRENT
				|| !"old".equals(compound.getStringOr("name", ""))
				|| compound.getIntOr("size", -1) != 1
				|| compound.contains("label")) {
			throw helper.assertionException("a migrated value must be written in the current shape at version " + CURRENT
					+ ", got " + written);
		}
		Versioned<Thing> current = decode(WITH_PREVIOUS, encode(WITH_PREVIOUS, Versioned.of(new Thing("new", 7))));
		if (!(current instanceof Readable<Thing> currentReadable) || !new Thing("new", 7).equals(currentReadable.value())) {
			throw helper.assertionException("a current body must still decode through the current codec, got " + current);
		}
		helper.succeed();
	}

	@GameTest
	public void aMalformedPreviousBodyIsUnreadableAndKeepsItsData(GameTestHelper helper) {
		CompoundTag malformed = compound(OLD, "name", "this is the version 2 field, not the label");
		expectUnreadable(helper, WITH_PREVIOUS, malformed, "a malformed version 1 body");
		CompoundTag wrongType = new CompoundTag();
		wrongType.putInt(Versioned.VERSION_KEY, OLD);
		wrongType.putInt("label", 5);
		expectUnreadable(helper, WITH_PREVIOUS, wrongType, "a version 1 body with a wrongly typed field");
		helper.succeed();
	}

	@GameTest
	public void anUnknownOrMissingVersionIsUnreadable(GameTestHelper helper) {
		expectUnreadable(helper, WITH_PREVIOUS, currentShaped(7), "an unknown version");
		expectUnreadable(helper, WITH_PREVIOUS, currentShaped(0), "version 0, which no codec reads");
		expectUnreadable(helper, WITH_PREVIOUS, currentShaped(null), "a missing version");
		helper.succeed();
	}

	@GameTest
	public void theTwoArgumentCodecIsUnchanged(GameTestHelper helper) {
		Codec<Versioned<Thing>> plain = Versioned.codec(CURRENT, Thing.BODY);
		Tag written = encode(plain, Versioned.of(new Thing("a", 3)));
		if (!(written instanceof CompoundTag compound) || compound.getIntOr(Versioned.VERSION_KEY, -1) != CURRENT) {
			throw helper.assertionException("the two-argument codec must write the current version, got " + written);
		}
		if (!(decode(plain, written) instanceof Readable<Thing> readable) || !new Thing("a", 3).equals(readable.value())) {
			throw helper.assertionException("the two-argument codec must round-trip a current value");
		}
		expectUnreadable(helper, plain, currentShaped(OLD), "an older version with no previous codecs");
		expectUnreadable(helper, plain, compound(CURRENT, "label", "wrong"), "a malformed current body");
		expectUnreadable(helper, plain, currentShaped(null), "a missing version");
		helper.succeed();
	}
}
