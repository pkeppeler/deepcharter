package io.github.pkeppeler.deepcharter.terminal;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.terminal.TerminalType.Access;

/**
 * Every kind of terminal. {@link #register} makes the block and its item, and adds the block to the one block entity type.
 * A type's repair state is world-wide (SPEC section 3), and parts go in only once the prerequisite is repaired, so a chain of
 * types is a repair order.
 *
 * <p>A type with no repair ({@link #registerAlwaysOnline}) works from the start, as the contract terminal of #72 does.
 *
 * <p>The four colony terminals are registered here, in the order the world is repaired: pump, processor, upgrade, repair
 * (#59). Their issues add actions and screens to them: {@link TerminalActions#register}, and on the client
 * {@code TerminalScreens.register}. Register more types only while mods initialise, before registries freeze.
 */
public final class TerminalTypes {
	private static final Map<Identifier, TerminalType> TYPES = new LinkedHashMap<>();
	private static final Set<Item> USED_PARTS = new HashSet<>();

	public static final TerminalType FUEL_PUMP = register(id("fuel_pump"), List.of(TerminalParts.PUMP_MOTOR, TerminalParts.FUEL_VALVE));
	public static final TerminalType ORE_PROCESSOR = register(id("ore_processor"),
			List.of(TerminalParts.SMELTER_COIL, TerminalParts.CRUSHER_GEAR, TerminalParts.ORE_FEEDER), FUEL_PUMP);
	public static final TerminalType UPGRADE_TERMINAL = register(id("upgrade_terminal"),
			List.of(TerminalParts.UPGRADE_BOARD, TerminalParts.SOCKET_ARRAY), ORE_PROCESSOR);
	public static final TerminalType REPAIR_STATION = register(id("repair_station"),
			List.of(TerminalParts.WELDING_ARM, TerminalParts.SERVO_UNIT, TerminalParts.REPAIR_CIRCUIT), UPGRADE_TERMINAL);

	private TerminalTypes() {
	}

	/** Loads the class, which registers the colony terminals. */
	public static void register() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
	}

	/** Registers the first terminal of a chain, for charters only: its parts can go in at once. */
	public static TerminalType register(Identifier id, List<Item> parts) {
		return create(id, Access.CHARTER_ONLY, Optional.of(new TerminalType.Repair(parts, Optional.empty())));
	}

	/** Registers a terminal, for charters only, whose parts go in only once {@code prerequisite} is repaired. */
	public static TerminalType register(Identifier id, List<Item> parts, TerminalType prerequisite) {
		return create(id, Access.CHARTER_ONLY, Optional.of(new TerminalType.Repair(parts, Optional.of(prerequisite))));
	}

	/** Registers a terminal that needs no repair: it is online from the start, with no offline screen. */
	public static TerminalType registerAlwaysOnline(Identifier id, Access access) {
		return create(id, access, Optional.empty());
	}

	private static TerminalType create(Identifier id, Access access, Optional<TerminalType.Repair> repair) {
		if (TYPES.containsKey(id)) {
			throw new IllegalArgumentException("terminal type " + id + " is already registered");
		}
		List<Item> parts = repair.map(TerminalType.Repair::parts).orElse(List.of());
		if (repair.isPresent() && parts.isEmpty()) {
			throw new IllegalArgumentException("terminal type " + id + " needs at least one part");
		}
		if (new HashSet<>(parts).size() != parts.size()) {
			throw new IllegalArgumentException("terminal type " + id + " lists a part twice: " + parts);
		}
		for (Item part : parts) {
			if (USED_PARTS.contains(part)) {
				throw new IllegalArgumentException("terminal type " + id + " uses " + TerminalType.partId(part) + ", a part of another terminal");
			}
		}
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
		// Unbreakable like bedrock in survival (hardness -1), and it takes a blast no vanilla block survives.
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, new TerminalBlock(BlockBehaviour.Properties.of()
				.setId(blockKey).strength(-1.0F, 3_600_000.0F).sound(SoundType.METAL).noLootTable()));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
		Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		TerminalBlockEntity.TYPE.addValidBlock(block);
		TerminalType type = new TerminalType(id, block, access, repair);
		TYPES.put(id, type);
		USED_PARTS.addAll(parts);
		return type;
	}

	/** Every type, in the order registered. */
	public static List<TerminalType> all() {
		return List.copyOf(TYPES.values());
	}

	public static Optional<TerminalType> get(Identifier id) {
		return Optional.ofNullable(TYPES.get(id));
	}

	public static Optional<TerminalType> of(Block block) {
		return TYPES.values().stream().filter(type -> type.block() == block).findFirst();
	}
}
