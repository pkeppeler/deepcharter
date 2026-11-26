package io.github.pkeppeler.deepcharter.wreck;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import java.util.Set;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Registers the wreck state and wires the wreck into the pod through {@link PodEvents}: it never edits the pod. The
 * state is synced to every client that tracks the pod, so the client can ask {@code PodEvents.isPowered} too.
 */
public final class WreckRegistry {
    /** Persistent and synced. Read it through {@link Wrecks#isWreck}, never raw: it may be {@code Unreadable}. */
    public static final AttachmentType<Versioned<WreckState>> STATE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "wreck"),
            builder -> builder.persistent(Versioned.codec(WreckState.VERSION, WreckState.BODY))
                    .initializer(() -> Versioned.of(WreckState.INTACT))
                    .syncWith(
                            Versioned.streamCodec(WreckState.VERSION, WreckState.STREAM),
                            AttachmentSyncPredicate.all()));

    /**
     * What a wreck kills its crew with ({@link CrewFate}): vanilla's {@code generic_kill} in all but name (its tags let it through armour,
     * resistance and invulnerability, as a kill), so the Continuity Event can say HULL LOSS (#1337).
     */
    public static final ResourceKey<DamageType> HULL_LOSS = ResourceKey.create(
            Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hull_loss"));

    /** Where a broken storage block's contents are kept ({@link SalvageHeaps}). */
    public static final Block HEAP = registerHeap();

    public static final BlockEntityType<SalvageHeapBlockEntity> HEAP_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            heapId(),
            new BlockEntityType<>(SalvageHeapBlockEntity::new, Set.of(HEAP)));

    private WreckRegistry() {}

    private static Identifier heapId() {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "salvage_heap");
    }

    private static Block registerHeap() {
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, heapId());
        Block block = Registry.register(
                BuiltInRegistries.BLOCK,
                blockKey,
                new SalvageHeapBlock(BlockBehaviour.Properties.of()
                        .setId(blockKey)
                        .strength(2.0F)
                        .sound(SoundType.WOOD)
                        // What a heap holds drops from its block entity when it breaks; the block itself drops nothing.
                        .noLootTable()));
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, heapId());
        Registry.register(
                BuiltInRegistries.ITEM,
                itemKey,
                new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
        return block;
    }

    public static void register() {
        CrewFate.init();
        PodEvents.HULL_DEPLETED.register(Wrecks::onHullDepleted);
        PodEvents.CAN_MOUNT.register((pod, passenger) -> !Wrecks.isWreck(pod));
        PodEvents.IS_POWERED.register(pod -> !Wrecks.isWreck(pod));
        ServerEntityEvents.ENTITY_LOAD.register(Wrecks::onLoad);
        UseEntityCallback.EVENT.register(Wrecks::onUse);
    }
}
