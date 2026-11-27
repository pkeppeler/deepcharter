package io.github.pkeppeler.deepcharter.repair;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.pod.TetherItem;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Registers the consumables, the thrown kit's projectile and flare block, the teleporters' arrival ticket, and the
 * repair station's actions.
 */
public final class RepairRegistry {
    private static final Map<Consumable, Item> ITEMS = new EnumMap<>(Consumable.class);

    static {
        for (Consumable consumable : Consumable.values()) {
            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, consumable.itemId());
            Item item = switch (consumable.kind()) {
                case TOOL -> tool(consumable, new Item.Properties().setId(key).stacksTo(1));
                // The bolts are ammunition the driver spends: a plain item that stacks to a pack.
                case CONSUMED ->
                    consumable == Consumable.BOLT
                            ? new Item(new Item.Properties().setId(key).stacksTo(64))
                            : new ConsumableItem(
                                    consumable, new Item.Properties().setId(key).stacksTo(16));
            };
            ITEMS.put(consumable, Registry.register(BuiltInRegistries.ITEM, key, item));
        }
    }

    private static final ResourceKey<EntityType<?>> THROWN_KIT_KEY = ResourceKey.create(
            Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "thrown_kit"));

    /** What a flare, decoy or charge is while it flies and lies. Not saved: one lost to an unloaded chunk is spent, as the pod's fuse is. */
    public static final EntityType<ThrownKitEntity> THROWN_KIT = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            THROWN_KIT_KEY,
            EntityType.Builder.<ThrownKitEntity>of(ThrownKitEntity::new, MobCategory.MISC)
                    .sized(0.25f, 0.25f)
                    .noSave()
                    .clientTrackingRange(8)
                    .updateInterval(10)
                    .build(THROWN_KIT_KEY));

    private static final ResourceKey<Block> FLARE_LIGHT_KEY =
            ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "flare_light"));

    /** A burning flare: light, and no item, since nothing places it by hand. It puts itself out ({@link FlareLightBlock}). */
    public static final Block FLARE_LIGHT = Registry.register(
            BuiltInRegistries.BLOCK,
            FLARE_LIGHT_KEY,
            new FlareLightBlock(
                    BlockBehaviour.Properties.of().setId(FLARE_LIGHT_KEY).sound(SoundType.WOOD)));

    /**
     * Holds a teleporter's landing chunk (#1353, {@link Teleports}) until the pilot's own chunk tickets arrive: it
     * loads and simulates the chunks, keeps the dimension active, and is not saved. It lapses
     * {@link RepairTuning#arrivalTicketTicks()} ticks after its chunk is ready to save ({@code ChunkHolder.saveSync}
     * settles): vanilla counts a timed ticket down only from then, well after the chunk ticks entities.
     */
    static final TicketType TELEPORT_ARRIVAL = Registry.register(
            BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "teleport_arrival"),
            new TicketType(
                    RepairTuning.DEFAULT.arrivalTicketTicks(),
                    TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    private RepairRegistry() {}

    private static Item tool(Consumable consumable, Item.Properties properties) {
        return switch (consumable) {
            case TETHER -> new TetherItem(properties);
            case BOLT_DRIVER -> new BoltDriverItem(properties);
            default -> throw new IllegalStateException(consumable + " is not a tool");
        };
    }

    public static void register() {
        RepairStation.register();
    }

    public static Item item(Consumable consumable) {
        return Objects.requireNonNull(
                ITEMS.get(consumable),
                "no item registered for " + consumable + "; the static block registers every Consumable");
    }
}
