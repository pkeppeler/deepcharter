package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.PodTowing.Refusal;
import io.github.pkeppeler.deepcharter.pod.TowReachProbe;
import io.github.pkeppeler.deepcharter.pod.TowTuning;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #1605, the tow cable on foot: a person who is out of every pod right-clicks one pod with the
 * cable, which picks it as the tower, and then another, which it tows. A seated use does nothing (the crosshair of a
 * seated player meets their own hull, so the seat was no place to aim a cable from). Every step says what happened, and
 * a refused hook says why and keeps the first pod.
 */
public class TowCableOnFootTest {
    private static final AtomicInteger NAMES = new AtomicInteger();
    /** Beyond the longest cable's reach of the first pod, which is at z 1.5. */
    private static final Vec3 FAR = new Vec3(1.5, 2, 12.5);

    /** Blocks a test's ray reaches. */
    private static final double RAY = 4.5;

    private static final Vec3 FIRST = new Vec3(1.5, 2, 1.5);
    private static final Vec3 SECOND = new Vec3(1.5, 2, 4.5);

    @GameTest
    public void twoRightClicksOnFootHookTheFirstPodToTheSecond(GameTestHelper helper) {
        PodEntity first = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity second = helper.spawn(PodRegistry.POD, SECOND);
        MockPlayer person = onFoot(helper);
        expect(helper, use(person, first).consumesAction(), "the first click should pick the pod");
        expect(
                helper,
                !PodTowing.isTowed(first) && !PodTowing.isTowed(second),
                "one click on a pod hooks nothing, got %s",
                PodTowing.towerId(second));
        expect(helper, use(person, second).consumesAction(), "the second click should hook the pods");
        expect(
                helper,
                Optional.of(first.getUUID()).equals(PodTowing.towerId(second)),
                "the first pod should tow the second, got %s",
                PodTowing.towerId(second));
        expect(helper, !PodTowing.isTowed(first), "the first pod is the tower");
        expect(
                helper,
                person.player().getItemInHand(InteractionHand.MAIN_HAND).is(PodRegistry.TOW_CABLE),
                "the cable is a tool and is not used up");
        helper.runAfterDelay(2, () -> {
            expectMessages(helper, person, List.of(say("picked"), say("attached")));
            helper.succeed();
        });
    }

    @GameTest
    public void aCableUsedFromTheSeatHooksNothingAndSaysToGetOut(GameTestHelper helper) {
        PodEntity seat = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity other = helper.spawn(PodRegistry.POD, SECOND);
        MockPlayer person = onFoot(helper);
        person.teleportTo(helper.getLevel(), seat.position(), 0f, 0f);
        expect(helper, person.player().startRiding(seat), "the person could not mount the pod");
        // What a seated crosshair meets is the ridden pod itself, then, past it, another.
        expect(helper, !use(person, seat).consumesAction(), "a cable used on the ridden pod should be refused");
        expect(helper, !use(person, other).consumesAction(), "a cable used from the seat should be refused");
        expect(helper, !use(person, other).consumesAction(), "and again, with a pod picked or not");
        expect(
                helper,
                !PodTowing.isTowed(seat) && !PodTowing.isTowed(other),
                "no cable is fitted from the seat, got %s",
                PodTowing.towerId(other));
        helper.runAfterDelay(2, () -> {
            Component getOut = refused("not_on_foot");
            expectMessages(helper, person, List.of(getOut, getOut, getOut));
            helper.succeed();
        });
    }

    @GameTest
    public void aHookThatIsRefusedSaysWhyAndKeepsTheFirstPod(GameTestHelper helper) {
        PodEntity first = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity second = helper.spawn(PodRegistry.POD, SECOND);
        PodEntity far = helper.spawn(PodRegistry.POD, FAR);
        MockPlayer person = onFoot(helper);
        use(person, first);
        use(person, first);
        use(person, far);
        expect(helper, !PodTowing.isTowed(far), "a pod out of reach is not hooked");
        expect(helper, !PodTowing.isTowed(first), "a pod does not tow itself");
        // The first pod is still picked: the next click hooks it.
        expect(helper, use(person, second).consumesAction(), "the pick should survive a refused hook");
        expect(
                helper,
                Optional.of(first.getUUID()).equals(PodTowing.towerId(second)),
                "the hook after two refusals should still tow from the first pod, got %s",
                PodTowing.towerId(second));
        helper.runAfterDelay(2, () -> {
            expectMessages(
                    helper,
                    person,
                    List.of(say("picked"), refused("same_pod"), Refusal.TOO_FAR.message(), say("attached")));
            helper.succeed();
        });
    }

    @GameTest
    public void aPodThatAlreadyTowsOrIsTowedIsRefusedAtTheFirstClick(GameTestHelper helper) {
        PodEntity first = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity second = helper.spawn(PodRegistry.POD, SECOND);
        PodEntity third = helper.spawn(PodRegistry.POD, new Vec3(4.5, 2, 4.5));
        MockPlayer person = onFoot(helper);
        PodTowing.attach(first, second);
        use(person, first);
        expect(helper, !PodTowing.isTowed(third), "a pod that tows one pod is not picked again");
        // The cable is not in the way of picking the one that is free: first (towing) is refused, third is picked.
        use(person, third);
        use(person, first);
        expect(
                helper,
                !Optional.of(third.getUUID()).equals(PodTowing.towerId(first)),
                "a pod that tows cannot be towed");
        helper.runAfterDelay(2, () -> {
            expectMessages(helper, person, List.of(refused("already_towing"), say("picked"), refused("towed_tows")));
            helper.succeed();
        });
    }

    @GameTest
    public void aPodOfAnotherCharterIsRefusedAtBothClicks(GameTestHelper helper) {
        PodEntity open = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity foreign = helper.spawn(PodRegistry.POD, SECOND);
        MockPlayer stranger = onFoot(helper);
        PodComponents.register(foreign, someCharter(helper));
        expect(
                helper,
                !PodComponents.mayAccess(foreign, Optional.empty()),
                "the test needs a pod the stranger may not access");
        use(stranger, open);
        use(stranger, foreign);
        expect(helper, !PodTowing.isTowed(foreign), "another charter's pod is not towed");
        // A click on nothing soon after the pick is read as the item use of the same click, so the put-away waits.
        helper.runAfterDelay(8, () -> {
            useInAir(helper, stranger);
            use(stranger, foreign);
            use(stranger, open);
            expect(helper, !PodTowing.isTowed(open), "and it is not the tower of anything either");
            helper.runAfterDelay(2, () -> {
                Component outsider = refused("not_allowed_to_tow");
                expectMessages(
                        helper, stranger, List.of(say("picked"), outsider, say("put_away"), outsider, say("picked")));
                helper.succeed();
            });
        });
    }

    @GameTest
    public void aClickOnNothingPutsTheCableAwayAndTheFirstPodIsForgotten(GameTestHelper helper) {
        PodEntity first = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity second = helper.spawn(PodRegistry.POD, SECOND);
        MockPlayer person = onFoot(helper);
        InteractionResult none = useInAir(helper, person);
        expect(helper, !none.consumesAction(), "a cable used with nothing picked has nothing to put away");
        use(person, first);
        helper.runAfterDelay(8, () -> {
            expect(helper, useInAir(helper, person).consumesAction(), "a click on nothing should put the cable away");
            use(person, second);
            expect(
                    helper,
                    !PodTowing.isTowed(first) && !PodTowing.isTowed(second),
                    "a click on a pod after the cable was put away picks it, and hooks nothing");
            helper.runAfterDelay(2, () -> {
                expectMessages(helper, person, List.of(say("picked"), say("put_away"), say("picked")));
                helper.succeed();
            });
        });
    }

    @GameTest
    public void aWreckIsTowedHomeByTheWorkingPodPickedFirst(GameTestHelper helper) {
        PodEntity working = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity wreck = helper.spawn(PodRegistry.POD, SECOND);
        wreck.setHull(0f);
        MockPlayer person = onFoot(helper);
        use(person, wreck);
        expect(helper, !PodTowing.isTowed(working), "a wreck cannot be the tower");
        use(person, working);
        use(person, wreck);
        expect(
                helper,
                Optional.of(working.getUUID()).equals(PodTowing.towerId(wreck)),
                "the working pod should tow the wreck, got %s",
                PodTowing.towerId(wreck));
        expect(
                helper,
                use(person, wreck).consumesAction() && !PodTowing.isTowed(wreck),
                "a click on the towed wreck takes the cable off, as for any pod");
        helper.runAfterDelay(2, () -> {
            expectMessages(
                    helper,
                    person,
                    List.of(refused("tower_is_wreck"), say("picked"), say("attached"), say("detached")));
            helper.succeed();
        });
    }

    @GameTest
    public void aCrewmateInEitherPodIsToldWhileTheirCharterMateHooksOnFoot(GameTestHelper helper) {
        PodEntity first = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity second = helper.spawn(PodRegistry.POD, SECOND);
        MockPlayer person = onFoot(helper);
        MockPlayer towerCrew = onFoot(helper);
        MockPlayer towedCrew = onFoot(helper);
        CharterId charter = charterOf(helper, person);
        MinecraftServer server = helper.getLevel().getServer();
        for (MockPlayer mate : List.of(towerCrew, towedCrew)) {
            UUID id = mate.player().getUUID();
            expect(helper, Charters.apply(server, id, charter).isEmpty(), "the crewmate should apply");
            expect(
                    helper,
                    Charters.approve(server, person.player().getUUID(), id).isEmpty(),
                    "the founder should approve the crewmate");
        }
        PodComponents.register(first, charter);
        PodComponents.register(second, charter);
        towerCrew.teleportTo(helper.getLevel(), first.position(), 0f, 0f);
        towedCrew.teleportTo(helper.getLevel(), second.position(), 0f, 0f);
        expect(helper, towerCrew.player().startRiding(first), "the crewmate could not mount the first pod");
        expect(helper, towedCrew.player().startRiding(second), "the crewmate could not mount the second pod");
        use(person, first);
        use(person, second);
        expect(
                helper,
                Optional.of(first.getUUID()).equals(PodTowing.towerId(second)),
                "a charter-mate on foot should hook pods that crew sit in, got %s",
                PodTowing.towerId(second));
        helper.runAfterDelay(2, () -> {
            expectMessages(helper, person, List.of(say("picked"), say("attached")));
            expectMessages(helper, towerCrew, List.of(say("towing_now")));
            expectMessages(helper, towedCrew, List.of(say("being_towed")));
            helper.succeed();
        });
    }

    /**
     * The seat's failure (#1605): the ridden pod's own box held the crosshair, so no other pod was ever picked. On foot
     * the person stands level with the first pod's east face, 0.3 blocks off it, and the second pod lies straight ahead
     * past that face. The ray runs along the first pod's box without entering it, so it must end on the second pod; a
     * ray aimed at the first must end on it. A pod directly in line would block a ray, as any entity does: the person
     * then steps aside.
     */
    @GameTest
    public void aPersonBesideAPodAimsPastItsEdgeAtTheNextPod(GameTestHelper helper) {
        double width = Chassis.MOLE.width();
        double standX = FIRST.x + width / 2 + 0.3;
        PodEntity first = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity second = helper.spawn(PodRegistry.POD, new Vec3(standX, FIRST.y, FIRST.z + 3.0));
        MockPlayer person = onFoot(helper);
        person.teleportTo(helper.getLevel(), helper.absoluteVec(new Vec3(standX, FIRST.y, FIRST.z - 0.5)), 0f, 0f);
        for (PodEntity wanted : List.of(second, first)) {
            Vec3 eye = person.player().getEyePosition();
            Vec3 toward = wanted.getBoundingBox().getCenter().subtract(eye).normalize();
            Vec3 end = eye.add(toward.scale(RAY));
            AABB search = person.player()
                    .getBoundingBox()
                    .expandTowards(toward.scale(RAY))
                    .inflate(1.0);
            EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                    person.player(),
                    eye,
                    end,
                    search,
                    entity -> entity.isPickable() && !entity.isSpectator(),
                    RAY * RAY);
            expect(
                    helper,
                    hit != null && hit.getEntity() == wanted,
                    "a ray from beside the pods to %s should end on it, got %s",
                    wanted.getUUID(),
                    hit == null ? null : hit.getEntity().getUUID());
        }
        expect(
                helper,
                second.getBoundingBox().minZ - first.getBoundingBox().maxZ > 1.0,
                "the pods should not touch, or the ray test says nothing");
        helper.succeed();
    }

    /**
     * One right-click on a pod: the use of the pod, then, as Minecraft does when the client's result passes, the item's
     * own use, in the same tick.
     */
    @GameTest
    public void aPickSurvivesTheItemUseThatFollowsTheClickOnThePodInTheSameTick(GameTestHelper helper) {
        PodEntity first = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity second = helper.spawn(PodRegistry.POD, SECOND);
        MockPlayer person = onFoot(helper);
        use(person, first);
        expect(helper, useInAir(helper, person).consumesAction(), "the item use that follows is consumed");
        expect(helper, PodTowing.pickedPodId(person.player()).isPresent(), "the pick must survive the same click");
        helper.runAfterDelay(2, () -> {
            expect(helper, PodTowing.pickedPodId(person.player()).isPresent(), "and the ticks after it");
            use(person, second);
            expect(helper, PodTowing.isTowed(second), "so the second click hooks");
            helper.succeed();
        });
    }

    @GameTest
    public void aPickedPodThatIsRemovedLapsesAndTheNextClickPicksAgain(GameTestHelper helper) {
        PodEntity first = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity second = helper.spawn(PodRegistry.POD, SECOND);
        MockPlayer person = onFoot(helper);
        use(person, first);
        first.discard();
        use(person, second);
        expect(helper, !PodTowing.isTowed(second), "a pod that was removed tows nothing");
        helper.runAfterDelay(2, () -> {
            expectMessages(helper, person, List.of(say("picked"), say("lapsed"), say("picked")));
            helper.succeed();
        });
    }

    @GameTest
    public void aTowerThatBecomesTowedBetweenTheClicksIsRefused(GameTestHelper helper) {
        PodEntity first = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity second = helper.spawn(PodRegistry.POD, SECOND);
        PodEntity third = helper.spawn(PodRegistry.POD, new Vec3(4.5, 2, 1.5));
        MockPlayer person = onFoot(helper);
        use(person, first);
        PodTowing.attach(third, first);
        use(person, second);
        expect(helper, !PodTowing.isTowed(second), "a pod that is being towed cannot tow");
        helper.runAfterDelay(2, () -> {
            expectMessages(helper, person, List.of(say("picked"), refused("tower_is_towed")));
            helper.succeed();
        });
    }

    @GameTest
    public void aCableInTheOffHandHooksAndKeepsItsPick(GameTestHelper helper) {
        PodEntity first = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity second = helper.spawn(PodRegistry.POD, SECOND);
        MockPlayer person = onFoot(helper);
        person.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        person.player().setItemInHand(InteractionHand.OFF_HAND, new ItemStack(PodRegistry.TOW_CABLE));
        UseEntity.on(person.player(), first.level(), InteractionHand.OFF_HAND, first);
        helper.runAfterDelay(3, () -> {
            expect(
                    helper,
                    PodTowing.pickedPodId(person.player()).isPresent(),
                    "a cable in the off hand keeps its pick");
            UseEntity.on(person.player(), second.level(), InteractionHand.OFF_HAND, second);
            expect(
                    helper,
                    Optional.of(first.getUUID()).equals(PodTowing.towerId(second)),
                    "the off-hand cable should hook, got %s",
                    PodTowing.towerId(second));
            helper.succeed();
        });
    }

    @GameTest
    public void aPickLapsesWithTimeAndWhenTheCableLeavesTheHandsOrThePersonBoards(GameTestHelper helper) {
        PodEntity first = helper.spawn(PodRegistry.POD, FIRST);
        PodEntity second = helper.spawn(PodRegistry.POD, SECOND);
        MockPlayer slow = onFoot(helper);
        MockPlayer fumbler = onFoot(helper);
        MockPlayer rider = onFoot(helper);
        use(slow, first);
        TowReachProbe.agePick(slow.player(), TowTuning.DEFAULT.pickTicks() + 1L);
        use(fumbler, first);
        fumbler.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        use(rider, first);
        rider.teleportTo(helper.getLevel(), second.position(), 0f, 0f);
        expect(helper, rider.player().startRiding(second), "the person could not mount the pod");
        helper.runAfterDelay(3, () -> {
            expect(helper, PodTowing.pickedPodId(slow.player()).isEmpty(), "a pick lapses with time");
            expect(
                    helper,
                    PodTowing.pickedPodId(fumbler.player()).isEmpty(),
                    "a pick ends with the cable out of the hands");
            expect(helper, PodTowing.pickedPodId(rider.player()).isEmpty(), "a pick ends when the person boards a pod");
            fumbler.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PodRegistry.TOW_CABLE));
            use(slow, second);
            use(fumbler, second);
            expect(helper, !PodTowing.isTowed(second), "a pick that ended hooks nothing: the next click only picks");
            helper.runAfterDelay(2, () -> {
                expectMessages(helper, slow, List.of(say("picked"), say("lapsed"), say("picked")));
                expectMessages(helper, fumbler, List.of(say("picked"), say("picked")));
                helper.succeed();
            });
        });
    }

    private static MockPlayer onFoot(GameTestHelper helper) {
        MockPlayer person = MockPlayers.join(helper, "tow-foot-" + NAMES.incrementAndGet());
        person.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PodRegistry.TOW_CABLE));
        return person;
    }

    private static InteractionResult use(MockPlayer person, PodEntity pod) {
        return UseEntity.on(person.player(), pod.level(), InteractionHand.MAIN_HAND, pod);
    }

    /** A right-click on empty ground or air: the item's own use, which Minecraft falls back to when nothing was hit. */
    private static InteractionResult useInAir(GameTestHelper helper, MockPlayer person) {
        return person.player()
                .getItemInHand(InteractionHand.MAIN_HAND)
                .use(helper.getLevel(), person.player(), InteractionHand.MAIN_HAND);
    }

    private static Component say(String key) {
        return Component.translatable("message.deepcharter.towing." + key);
    }

    private static Component refused(String key) {
        return Component.translatable("message.deepcharter.towing.refused." + key);
    }

    private static void expectMessages(GameTestHelper helper, MockPlayer person, List<Component> expected) {
        if (!expected.equals(person.actionBarMessages())) {
            throw failure(
                    helper,
                    "the person should be told what each click did: %s, got %s",
                    expected,
                    person.actionBarMessages());
        }
    }

    private static CharterId someCharter(GameTestHelper helper) {
        return found(helper, UUID.randomUUID());
    }

    private static CharterId charterOf(GameTestHelper helper, MockPlayer person) {
        return found(helper, person.player().getUUID());
    }

    private static CharterId found(GameTestHelper helper, UUID founder) {
        MinecraftServer server = helper.getLevel().getServer();
        if (Charters.found(server, founder, "Tow On Foot " + NAMES.incrementAndGet())
                .isPresent()) {
            throw failure(helper, "founding the charter should succeed");
        }
        return Charters.charterOfOrThrow(server, founder).orElseThrow().id();
    }
}
