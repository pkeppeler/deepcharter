package io.github.pkeppeler.deepcharter.test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.fuel.ReserveTank;
import io.github.pkeppeler.deepcharter.handbook.HandbookReadPayload;
import io.github.pkeppeler.deepcharter.handbook.HandbookRegistry;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestAttachments;
import io.github.pkeppeler.deepcharter.test.support.TestAttachments.Example;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.wreck.WreckRegistry;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * The safe accessor {@link Versioned#readable}, and the attachment-backed features' tick, join and callback paths against
 * unreadable data. The SavedData-backed features (charters, handbook progress, notes, colony, work orders, serials, repair state,
 * transmissions, hangar, pod lights) and pod cargo have their own tests, listed in the PR.
 */
public class UnreadableAccessTest {
	private static final Identifier ENTRY = Identifier.fromNamespaceAndPath("deepcharter_test", "an_entry");

	@GameTest
	public void readableGivesTheValueOrNothingAndLogsOncePerOwner(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		PodEntity other = helper.spawn(PodRegistry.POD, 4, 2, 2);
		try {
			if (!Optional.of(Example.DEFAULT).equals(Versioned.readable(pod, TestAttachments.EXAMPLE))) {
				throw helper.assertionException("an unset attachment reads as its initial value");
			}
			if (pod.getAttached(TestAttachments.EXAMPLE) != null) {
				throw helper.assertionException("reading must not create the attachment");
			}
			pod.setAttached(TestAttachments.EXAMPLE, Versioned.of(new Example(3)));
			if (!Optional.of(new Example(3)).equals(Versioned.readable(pod, TestAttachments.EXAMPLE))) {
				throw helper.assertionException("a readable attachment reads as its value");
			}

			UnreadableChecks.makeUnreadable(pod, TestAttachments.EXAMPLE);
			UnreadableChecks.makeUnreadable(other, TestAttachments.EXAMPLE);
			LogCapture first = LogCapture.start(pod.getUUID().toString());
			LogCapture second = LogCapture.start(other.getUUID().toString());
			for (int i = 0; i < 5; i++) {
				if (Versioned.readable(pod, TestAttachments.EXAMPLE).isPresent()) {
					throw helper.assertionException("an unreadable attachment reads as nothing");
				}
			}
			Versioned.readable(other, TestAttachments.EXAMPLE);
			if (first.errors().size() != 1 || second.errors().size() != 1) {
				throw helper.assertionException("each owner is logged once, got %s and %s", first.errors(), second.errors());
			}
			if (!first.errors().getFirst().contains(TestAttachments.EXAMPLE.identifier().toString())
					|| !first.errors().getFirst().contains(String.valueOf(UnreadableChecks.FUTURE_VERSION))) {
				throw helper.assertionException("the log names the attachment and the saved version: %s", first.errors());
			}
			helper.succeed();
		} finally {
			pod.discard();
			other.discard();
		}
	}

	@GameTest
	public void everyPodAttachmentUnreadableNeverThrowsFromATickAMountOrAHullBreak(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer mock = MockPlayers.join(helper, "unreadable-pod-paths");
		try {
			UnreadableChecks.makeUnreadable(pod, PodComponents.STATE);
			UnreadableChecks.makeUnreadable(pod, PodTowing.STATE);
			UnreadableChecks.makeUnreadable(pod, WreckRegistry.STATE);
			UnreadableChecks.makeUnreadable(pod, ReserveTank.STATE);
			Map<String, Runnable> paths = new LinkedHashMap<>();
			paths.put("stats", () -> PodStats.of(pod));
			paths.put("components", () -> {
				PodComponents.registration(pod);
				PodComponents.partOf(pod, ComponentTrack.HULL);
				PodComponents.effectiveTier(pod, ComponentTrack.RADIATOR);
				PodComponents.radiatorRatio(pod);
				PodComponents.ownerCharter(pod);
				PodComponents.mayAccess(pod, Optional.empty());
			});
			paths.put("mount", () -> PodEvents.canMount(pod, mock.player()));
			paths.put("towing", () -> {
				PodTowing.towerId(pod);
				PodTowing.isTowed(pod);
				PodTowing.refusal(pod, pod);
			});
			paths.put("wreck", () -> Wrecks.isWreck(pod));
			paths.put("reserve tank", () -> ReserveTank.isInstalled(pod));
			paths.put("tick", pod::tick);
			paths.put("hull break", () -> pod.setHull(0f));
			UnreadableChecks.assertNoThrow(helper, "pod attachments", paths);
			helper.succeed();
		} finally {
			mock.leave();
			pod.discard();
		}
	}

	@GameTest
	public void unreadableReadMarksNeverThrowFromAJoinOrAReadRequest(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer mock = MockPlayers.join(helper, "unreadable-read-marks");
		ServerPlayer player = mock.player();
		try {
			UnreadableChecks.makeUnreadable(player, HandbookRegistry.READ_MARKS);
			LogCapture logged = LogCapture.start(player.getUUID().toString());
			Map<String, Runnable> paths = new LinkedHashMap<>();
			paths.put("is read", () -> {
				if (ReadMarks.isRead(player, ENTRY)) {
					throw new IllegalStateException("unreadable marks show nothing as read");
				}
			});
			paths.put("read request", () -> {
				if (HandbookReadPayload.handle(server, player, ENTRY)) {
					throw new IllegalStateException("a read request marks nothing when the marks are unreadable");
				}
			});
			UnreadableChecks.assertNoThrow(helper, "read marks", paths);
			if (logged.errors().size() != 1) {
				throw helper.assertionException("the unreadable marks are logged once, got %s", logged.errors());
			}
			helper.succeed();
		} finally {
			mock.leave();
		}
	}
}
