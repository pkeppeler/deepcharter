package io.github.pkeppeler.deepcharter.layer;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

/**
 * The Prospectors that lie at the wreck sites ({@link StructureKind#WRECK}), drawn with the site (ADR 0025, ADR 0027). Each bay holds
 * an unowned Prospector at hull 0, in the middle that the blueprint leaves clear. The one at the site nearest the Conduit is
 * PROSPECTOR-0002, with one lamp still burning and Note N10. Any charter may tow a wreck nobody owns to the hangar and restore it.
 */
final class ProspectorWrecks {
	/** The name the nearest wreck carries until a charter restores it; the registration names it after that. */
	static final String FAMOUS_NAME = "PROSPECTOR-0002";
	private static final int NOTE = 10;

	private ProspectorWrecks() {
	}

	/**
	 * Sets the Prospector in the bay once, from the chunk that holds the site's origin. A pod that cannot be made is logged and
	 * the site stays empty: a chunk callback never throws.
	 */
	static void place(ServerLevel level, StructureSite site, ChunkPos chunk, boolean famous) {
		if (!ChunkPos.containing(site.origin()).equals(chunk)) {
			return;
		}
		PodEntity pod = PodRegistry.PROSPECTOR.create(level, EntitySpawnReason.STRUCTURE);
		if (pod == null) {
			DeepCharter.LOGGER.error("Could not make the Prospector for the wreck site at {}: the pod was not created", site.origin().toShortString());
			return;
		}
		pod.setPos(Vec3.atBottomCenterOf(site.origin()));
		pod.setHull(0f);
		if (famous) {
			pod.setCustomName(Component.literal(FAMOUS_NAME));
			pod.setCustomNameVisible(true);
		}
		if (!level.addFreshEntity(pod)) {
			DeepCharter.LOGGER.error("Could not place the Prospector at {}: the world refused the entity", site.origin().toShortString());
		}
	}

	/** PROSPECTOR-0002's bay: three lamps gone dark, one burning, and Ines's log (N10) on a table. */
	static void light(StructurePlan p) {
		p.set(3, 0, 2, Blocks.LANTERN.defaultBlockState());
		p.set(3, 0, -2, Blocks.REDSTONE_LAMP.defaultBlockState());
		p.set(-3, 0, -2, Blocks.REDSTONE_LAMP.defaultBlockState());
		p.set(-3, 0, 2, Blocks.REDSTONE_LAMP.defaultBlockState());
		p.set(0, 0, 4, Blocks.SPRUCE_PLANKS.defaultBlockState());
		p.set(0, 1, 4, NoteBlock.stateOf(NOTE));
	}
}
