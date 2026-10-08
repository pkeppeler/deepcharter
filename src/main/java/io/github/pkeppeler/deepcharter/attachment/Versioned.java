package io.github.pkeppeler.deepcharter.attachment;

import java.util.Optional;
import java.util.function.UnaryOperator;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.MapCodec;

import io.netty.buffer.ByteBuf;

import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The value type of every versioned attachment: either the current-version value, or the saved data
 * of a version this build cannot read, kept untouched.
 *
 * <p>Fabric loads all of an entity's attachments as one map and, if that map fails to decode,
 * silently drops all of them, and the next save then overwrites the real data. So a versioned
 * codec must never fail. {@link #codec} decodes an unknown or missing version, or a body that does
 * not parse, to {@link Unreadable}, and writes it back unchanged. Nothing is lost, and one feature's
 * bad data never takes another feature's attachments with it.
 *
 * <p>Register an attachment as {@code AttachmentType<Versioned<Foo>>} with
 * {@code persistent(Versioned.codec(VERSION, FOO_BODY))} and
 * {@code initializer(() -> Versioned.of(Foo.DEFAULT))}. Read it on a tick, join, sync or callback path
 * with {@link #readable}, which never throws and logs an unreadable value once. {@link #orThrow} and {@link #modifyOrThrow} throw
 * on {@link Unreadable}: they are for explicit calls and commands only. The body codec writes the fields but no
 * version: this class writes {@code "version"}.
 *
 * <p>To change a value's shape, bump the version and make the decode read the old one too. Versions
 * start at 1.
 */
public sealed interface Versioned<T> {
	String VERSION_KEY = "version";
	/** The wire stands in 0 for an unreadable value, so versions start at 1. */
	int UNREADABLE_ON_WIRE = 0;

	/** A value of the current version. */
	record Readable<T>(T value) implements Versioned<T> {
	}

	/** Saved data of another version (or a missing one, or one whose body did not parse), held as it was read. */
	record Unreadable<T>(Tag raw) implements Versioned<T> {
		/** The saved version, or "missing" when there is none. */
		public String version() {
			Tag version = raw instanceof CompoundTag compound ? compound.get(VERSION_KEY) : null;
			return version == null ? "missing" : String.valueOf(version);
		}
	}

	static <T> Versioned<T> of(T value) {
		return new Readable<>(value);
	}

	/** The disk codec. It never fails: see the class comment. */
	static <T> Codec<Versioned<T>> codec(int currentVersion, MapCodec<T> body) {
		requireValidVersion(currentVersion);
		return Codec.PASSTHROUGH.xmap(
				dynamic -> decode(currentVersion, body, dynamic),
				versioned -> encode(currentVersion, body, versioned));
	}

	/**
	 * The network codec. An unreadable value goes over as a bare marker and arrives as
	 * {@link Unreadable}, so a pod with bad saved data does not break its trackers. A value of a
	 * version other than ours throws, which disconnects the client: the two sides run different builds.
	 */
	static <T> StreamCodec<ByteBuf, Versioned<T>> streamCodec(int currentVersion, StreamCodec<ByteBuf, T> body) {
		requireValidVersion(currentVersion);
		return new StreamCodec<>() {
			@Override
			public Versioned<T> decode(ByteBuf buf) {
				int version = ByteBufCodecs.VAR_INT.decode(buf);
				if (version == UNREADABLE_ON_WIRE) {
					return new Unreadable<>(new CompoundTag());
				}
				if (version != currentVersion) {
					throw new IllegalStateException("synced attachment has version " + version + ", this build reads " + currentVersion);
				}
				return new Readable<>(body.decode(buf));
			}

			@Override
			public void encode(ByteBuf buf, Versioned<T> versioned) {
				switch (versioned) {
					case Readable<T> readable -> {
						ByteBufCodecs.VAR_INT.encode(buf, currentVersion);
						body.encode(buf, readable.value());
					}
					case Unreadable<T> unreadable -> ByteBufCodecs.VAR_INT.encode(buf, UNREADABLE_ON_WIRE);
				}
			}
		};
	}

	/**
	 * The safe accessor, and the one to use on a tick, join, sync or gameplay-callback path: the current-version value of
	 * {@code type} on {@code owner}, or empty when the saved data is unreadable. An unreadable value is logged once for each owner
	 * and attachment, and left as it is. An attachment that was never set reads as its initial value and is not created. The caller
	 * decides what empty means: skip, or the feature's stock behaviour. Never throws.
	 */
	static <T> Optional<T> readable(AttachmentTarget owner, AttachmentType<Versioned<T>> type) {
		Versioned<T> versioned = owner.getAttached(type);
		if (versioned == null) {
			versioned = type.initializer().get();
		}
		return switch (versioned) {
			case Readable<T> readable -> Optional.of(readable.value());
			case Unreadable<T> unreadable -> {
				UnreadableLog.once(owner, type, unreadable);
				yield Optional.empty();
			}
		};
	}

	/**
	 * The current-version value of {@code type} on {@code owner}. Throws if the saved data is unreadable, so it is for explicit
	 * calls and commands only; a tick, join, sync or callback path uses {@link #readable}.
	 */
	static <T> T orThrow(AttachmentTarget owner, AttachmentType<Versioned<T>> type) {
		return switch (owner.getAttachedOrCreate(type)) {
			case Readable<T> readable -> readable.value();
			case Unreadable<T> unreadable -> throw unreadable(owner, type, unreadable);
		};
	}

	/**
	 * Replaces the value of {@code type} on {@code owner}. Throws if the saved data is unreadable, so it is never overwritten: for
	 * explicit calls and commands only, or after {@link #readable} has said the value is there.
	 */
	static <T> T modifyOrThrow(AttachmentTarget owner, AttachmentType<Versioned<T>> type, UnaryOperator<T> change) {
		T updated = change.apply(orThrow(owner, type));
		owner.setAttached(type, of(updated));
		return updated;
	}

	private static <T> IllegalStateException unreadable(AttachmentTarget owner, AttachmentType<Versioned<T>> type, Unreadable<T> unreadable) {
		return new IllegalStateException("attachment " + type.identifier() + " on " + owner + " has saved version "
				+ unreadable.version() + " that this build cannot read");
	}

	private static <T> Versioned<T> decode(int currentVersion, MapCodec<T> body, Dynamic<?> dynamic) {
		Optional<Number> version = dynamic.get(VERSION_KEY).asNumber().result();
		if (version.isPresent() && version.get().intValue() == currentVersion) {
			Optional<T> value = body.codec().parse(dynamic).result();
			if (value.isPresent()) {
				return new Readable<>(value.get());
			}
		}
		return new Unreadable<>(dynamic.convert(NbtOps.INSTANCE).getValue());
	}

	private static <T> Dynamic<Tag> encode(int currentVersion, MapCodec<T> body, Versioned<T> versioned) {
		Tag tag = switch (versioned) {
			case Readable<T> readable -> {
				Tag encoded = body.codec().encodeStart(NbtOps.INSTANCE, readable.value()).getOrThrow();
				if (!(encoded instanceof CompoundTag compound)) {
					throw new IllegalStateException("a versioned attachment body must encode to a compound, got " + encoded);
				}
				compound.putInt(VERSION_KEY, currentVersion);
				yield compound;
			}
			case Unreadable<T> unreadable -> unreadable.raw();
		};
		return new Dynamic<>(NbtOps.INSTANCE, tag);
	}

	private static void requireValidVersion(int currentVersion) {
		if (currentVersion < 1) {
			throw new IllegalArgumentException("attachment versions start at 1, got " + currentVersion);
		}
	}
}
