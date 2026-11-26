package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.JUMP;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodDump;
import io.github.pkeppeler.deepcharter.pod.PodDumpPayload;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodMenu;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestCharters;
import io.github.pkeppeler.deepcharter.test.support.TestCleanups;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

/**
 * Server GameTests for #1456: dumping cargo from the seat. A stack thrown from the pod screen's hold, or the whole hold
 * by the dump key's request, drops as items under the pod: the mass falls and an overloaded pod lifts again. Ore never
 * reaches a pack while its carrier sits in a pod, though anyone on foot picks it up. Only a crew member seated in the
 * pod dumps it. The first overload with a pilot aboard tells that pilot once how to dump.
 *
 * <p>The clicks are the server menu's own {@code clicked}, which the click packet runs after its {@code stillValid}
 * check; the key's request goes through the payload's real receiver.
 */
public class PodDumpTest {
    private static final AtomicInteger PLAYERS = new AtomicInteger();
    private static final String HINT_KEY = "deepcharter.pod.dump.hint";
    /** Ticks past an item's pickup delay, so a pack that could take a dumped ore has had the chance. */
    private static final int PICKUP_WAIT = 40;
    /** An item entity's height: one resting on the ground the pod stands on lies at most this far above its floor. */
    private static final double ITEM_HEIGHT = 0.25;

    /** The pod's charter's pilot, seated, and the pod; both go when the test ends, however it ends. */
    private record Scene(MockPlayer mock, ServerPlayer pilot, PodEntity pod, CharterId charter) {}

    private static MockPlayer join(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "Dump" + PLAYERS.incrementAndGet());
        TestCleanups.onFinish(helper, mock::leave);
        mock.player().setGameMode(GameType.SURVIVAL);
        return mock;
    }

    private static Scene seated(GameTestHelper helper) {
        MockPlayer mock = join(helper);
        CharterId charter = TestCharters.found(helper, helper.getLevel().getServer(), mock.player());
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
        TestCleanups.onFinish(helper, pod::discard);
        PodComponents.register(pod, charter);
        mock.teleportTo((ServerLevel) pod.level(), pod.position(), 0f, 0f);
        expect(helper, mock.player().startRiding(pod, true, false), "the pilot should board");
        return new Scene(mock, mock.player(), pod, charter);
    }

    /** Puts one ironium in the hold that weighs as much as the engine lifts, so the pod is overloaded. */
    private static void overload(PodEntity pod) {
        pod.cargo()
                .tryAdd(
                        pod,
                        OreRegistry.stack(OreType.IRONIUM),
                        PodStats.of(pod).enginePower());
    }

    private static PodMenu open(GameTestHelper helper, ServerPlayer player, PodEntity pod) {
        Optional<Component> refusal = PodMenu.open(player, pod);
        expect(helper, refusal.isEmpty(), "the pod's crew should open its screen, was refused: %s", refusal);
        if (!(player.containerMenu instanceof PodMenu menu)) {
            throw failure(helper, "the pod screen should be open, %s is", player.containerMenu);
        }
        return menu;
    }

    /** Q on hold slot {@code window} of the page, as the click packet runs it. */
    private static void throwFromHold(PodMenu menu, ServerPlayer player, int window) {
        menu.clicked(menu.holdSlot(window).index, 0, ContainerInput.THROW, player);
    }

    /** The items lying in the box from a block under the pod's floor to its top, as wide as the pod and a block more. */
    private static List<ItemEntity> itemsUnder(PodEntity pod) {
        AABB box = pod.getBoundingBox().inflate(1.0, 0, 1.0).expandTowards(0, -1.5, 0);
        return pod.level().getEntitiesOfClass(ItemEntity.class, box);
    }

    private static int oreLying(PodEntity pod) {
        return itemsUnder(pod).stream()
                .filter(item -> OreRegistry.typeOf(item.getItem()).isPresent())
                .mapToInt(item -> item.getItem().getCount())
                .sum();
    }

    private static int oreHeld(ServerPlayer player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (OreRegistry.typeOf(stack).isPresent()) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static long hints(MockPlayer mock) {
        return mock.chatMessages().stream()
                .filter(message -> message.getContents() instanceof TranslatableContents contents
                        && contents.getKey().equals(HINT_KEY))
                .count();
    }

    // ---- dumping and lifting ----

    @GameTest(maxTicks = 120)
    public void anOverloadedPodDumpsAStackFromItsScreenAndLiftsAgain(GameTestHelper helper) {
        Scene scene = seated(helper);
        PodEntity pod = scene.pod();
        overload(pod);
        pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
        // Where the pod rests once it has settled, under the held jump: index 0 at tick 10, 1 at tick 20.
        double[] rest = new double[2];
        scene.mock().setInput(JUMP);
        helper.runAfterDelay(10, () -> rest[0] = pod.getY());
        helper.runAfterDelay(20, () -> {
            rest[1] = pod.getY();
            expect(
                    helper,
                    rest[1] - rest[0] < 0.01 && pod.getDeltaMovement().y <= 0,
                    "an overloaded pod must not lift under a held jump, rose %s at %s a tick",
                    rest[1] - rest[0],
                    pod.getDeltaMovement().y);
            float before = pod.cargoMass();
            PodMenu menu = open(helper, scene.pilot(), pod);
            throwFromHold(menu, scene.pilot(), 0);
            expect(
                    helper,
                    pod.cargoMass() < before && pod.cargo().stackAt(0).isEmpty() && pod.cargoUsed() == 1,
                    "Q on the heavy stack dumps it: mass %s from %s, slot 0 holds %s, %s used",
                    pod.cargoMass(),
                    before,
                    pod.cargo().stackAt(0),
                    pod.cargoUsed());
            scene.pilot().closeContainer();
        });
        // Read a few ticks into the climb, before the pod reaches the test's ceiling.
        helper.runAfterDelay(
                25,
                () -> expect(
                        helper,
                        pod.getY() - rest[1] > 0.05 && pod.getDeltaMovement().y > 0,
                        "once lighter the pod climbs under the same jump, rose %s at %s a tick",
                        pod.getY() - rest[1],
                        pod.getDeltaMovement().y));
        helper.runAfterDelay(26, helper::succeed);
    }

    @GameTest(maxTicks = 60)
    public void theDumpKeyDropsTheWholeHoldAsItemsUnderThePod(GameTestHelper helper) {
        Scene scene = seated(helper);
        PodEntity pod = scene.pod();
        overload(pod);
        pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
        scene.mock().send(new PodDumpPayload());
        helper.runAfterDelay(5, () -> {
            expect(
                    helper,
                    pod.cargoUsed() == 0 && pod.cargoMass() == 0f,
                    "the key empties the hold, %s used, mass %s",
                    pod.cargoUsed(),
                    pod.cargoMass());
            expect(
                    helper,
                    oreLying(pod) == 2,
                    "both ore lie under the pod as items, found %s",
                    itemsUnder(pod).stream().map(ItemEntity::getItem).toList());
            expect(
                    helper,
                    itemsUnder(pod).stream().allMatch(item -> item.getY() <= pod.getY() + ITEM_HEIGHT),
                    "the items lie on the ground under the pod's floor %s, at %s",
                    pod.getY(),
                    itemsUnder(pod).stream().map(ItemEntity::getY).toList());
            expect(
                    helper,
                    scene.mock()
                            .soundsHeardFrom(pod)
                            .contains(DeepSound.CARGO_JETTISON.event().location()),
                    "dumping sounds the jettison at the pod, heard %s",
                    scene.mock().soundsHeardFrom(pod));
            helper.succeed();
        });
    }

    // ---- ore stays out of the pack ----

    @GameTest(maxTicks = 160)
    public void dumpedOreNeverReachesASeatedPackButAPlayerOnFootPicksItUp(GameTestHelper helper) {
        Scene scene = seated(helper);
        PodEntity pod = scene.pod();
        pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
        pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
        PodMenu menu = open(helper, scene.pilot(), pod);
        throwFromHold(menu, scene.pilot(), 0);
        scene.pilot().closeContainer();
        scene.mock().send(new PodDumpPayload());
        helper.runAfterDelay(PICKUP_WAIT, () -> {
            expect(
                    helper,
                    oreHeld(scene.pilot()) == 0 && oreLying(pod) == 2 && pod.cargoUsed() == 0,
                    "the seated pilot's pack takes no dumped ore: %s held, %s lying, %s in the hold",
                    oreHeld(scene.pilot()),
                    oreLying(pod),
                    pod.cargoUsed());
            scene.pilot().stopRiding();
            ItemEntity first = itemsUnder(pod).getFirst();
            scene.mock().teleportTo((ServerLevel) pod.level(), first.position(), 0f, 0f);
        });
        helper.runAfterDelay(PICKUP_WAIT + 40, () -> {
            expect(
                    helper,
                    oreHeld(scene.pilot()) == 2,
                    "on foot the pilot picks the dumped ore up, holds %s, %s still lying",
                    oreHeld(scene.pilot()),
                    oreLying(pod));
            helper.succeed();
        });
    }

    // ---- who may dump ----

    @GameTest(maxTicks = 40)
    public void onlyACrewMemberSeatedInThePodDumpsIt(GameTestHelper helper) {
        Scene scene = seated(helper);
        PodEntity pod = scene.pod();
        pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
        scene.pilot().stopRiding();
        scene.mock().send(new PodDumpPayload());
        MockPlayer stranger = join(helper);
        TestCharters.found(helper, helper.getLevel().getServer(), stranger.player());
        stranger.teleportTo((ServerLevel) pod.level(), pod.position(), 0f, 0f);
        expect(helper, stranger.player().startRiding(pod, true, false), "the stranger should be seated");
        stranger.send(new PodDumpPayload());
        helper.runAfterDelay(10, () -> {
            expect(
                    helper,
                    pod.cargoUsed() == 1 && oreLying(pod) == 0,
                    "a crew member on foot and a seated stranger dump nothing: %s used, %s lying",
                    pod.cargoUsed(),
                    oreLying(pod));
            helper.succeed();
        });
    }

    // ---- the one-time hint ----

    @GameTest(maxTicks = 80)
    public void theFirstOverloadTellsThePilotOnceHowToDump(GameTestHelper helper) {
        Scene scene = seated(helper);
        PodEntity pod = scene.pod();
        pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
        helper.runAfterDelay(5, () -> {
            expect(helper, hints(scene.mock()) == 0, "a pod with lift to spare says nothing");
            overload(pod);
        });
        helper.runAfterDelay(10, () -> {
            expect(
                    helper,
                    hints(scene.mock()) == 1 && ReadMarks.isRead(scene.pilot(), PodDump.HINT_MARK),
                    "the first overload tells the pilot once, told %s times",
                    hints(scene.mock()));
            Component hint = scene.mock().chatMessages().stream()
                    .filter(message -> message.getContents() instanceof TranslatableContents contents
                            && contents.getKey().equals(HINT_KEY))
                    .findFirst()
                    .orElseThrow();
            Object[] args = ((TranslatableContents) hint.getContents()).getArgs();
            expect(
                    helper,
                    args.length == 1
                            && args[0] instanceof Component key
                            && key.getContents() instanceof KeybindContents bound
                            && bound.getName().equals(PodDump.KEY),
                    "the hint names the dump key as the player binds it, args %s",
                    List.of(args));
            scene.pilot().stopRiding();
        });
        helper.runAfterDelay(15, () -> scene.pilot().startRiding(pod, true, false));
        helper.runAfterDelay(50, () -> {
            expect(helper, hints(scene.mock()) == 1, "the hint never comes again, told %s times", hints(scene.mock()));
            helper.succeed();
        });
    }
}
