package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.MAX_TICKS;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.PARTS;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.RICH;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.act;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.balance;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.charterOf;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.clearFloor;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.console;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.count;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.deposit;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.expectDone;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.expectRefused;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.give;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.inTheHangar;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.madeSince;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.member;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.pod;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.repairTheConsole;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.server;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.stand;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.withFreshWorld;
import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.hangar.Hangar;
import io.github.pkeppeler.deepcharter.hangar.HangarData;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.hangar.HangarView;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.HangarPods;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TerminalParts;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.github.pkeppeler.deepcharter.transmission.Transmission;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.wreck.WreckPack;
import io.github.pkeppeler.deepcharter.wreck.WreckRegistry;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #1595 (ADR 0117): each charter member crafts one Mole from four parts and pays no fee, a wreck
 * keeps its owner's place and what it holds, only scrapping at the hangar or a write-off a day later frees the place,
 * and a world saved before the rule still loads.
 *
 * <p>Every test runs in a fresh hangar record inside the hangar's tick, as {@link FoundingMoleHangarTest} does.
 */
public class MoleSlotTest {
    private static final int ONE_DAY = 24_000;
    /** Pods whose removal this test makes fail; the listener below acts on no other pod. */
    private static final Set<UUID> FAILING = ConcurrentHashMap.newKeySet();

    static {
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (FAILING.contains(entity.getUUID())) {
                throw new IllegalStateException("a test makes the removal of " + entity.getUUID() + " fail");
            }
        });
    }
    /** Ticks after which the mock has recorded what the server told it. */
    private static final int MESSAGE_TICKS = 4;

    /** A refusal whose reason the player must be told: the mock records the message a tick after the action. */
    private record Told(MockPlayer mock, String reason, String what) {}

    private static void expectRefusedWith(
            GameTestHelper helper, List<Told> told, MockPlayer mock, BlockPos console, String reason, String what) {
        expectRefused(helper, act(mock.player(), console, HangarTerminal.ASSEMBLE_MOLE), what);
        told.add(new Told(mock, reason, what));
    }

    /** Ends the test a few ticks on, when every refused player has been sent the reason that was owed to them. */
    private static void finish(GameTestHelper helper, List<Told> told) {
        helper.runAfterDelay(MESSAGE_TICKS, () -> {
            for (Told owed : told) {
                expect(
                        helper,
                        owed.mock().actionBarMessages().stream()
                                .anyMatch(message -> message.getContents() instanceof TranslatableContents contents
                                        && contents.getKey().equals("deepcharter.hangar.refusal." + owed.reason())),
                        "%s should say %s, the player was told %s",
                        owed.what(),
                        owed.reason(),
                        owed.mock().actionBarMessages());
            }
            helper.succeed();
        });
    }

    /** Gives the player the four parts and assembles a Mole, which must work; returns it. */
    private static PodEntity assemble(
            GameTestHelper helper, Set<UUID> before, MockPlayer mock, BlockPos console, String what) {
        PARTS.forEach(part -> give(mock.player(), part, 1));
        int made = madeSince(helper, before).size();
        expectDone(helper, act(mock.player(), console, HangarTerminal.ASSEMBLE_MOLE), what);
        List<PodEntity> pods = madeSince(helper, before);
        expect(helper, pods.size() == made + 1, "%s makes one pod, the hangar holds %s new", what, pods.size());
        return pods.getLast();
    }

    /** Moves a Mole from the bay to the floor of the test, so the console at the test's floor has it in reach. */
    private static void bringHome(GameTestHelper helper, PodEntity pod) {
        pod.setPos(helper.absoluteVec(new Vec3(5.5, 2, 5.5)));
    }

    /** Presses Write Off twice, as the console asks: the first arms it, the second files it. */
    private static void fileWriteOff(GameTestHelper helper, MockPlayer owner, BlockPos console) {
        expectDone(helper, act(owner.player(), console, HangarTerminal.WRITE_OFF), "the arming press");
        expectDone(helper, act(owner.player(), console, HangarTerminal.WRITE_OFF), "the confirming press");
    }

    private static HangarData data(GameTestHelper helper) {
        return HangarData.readable(server(helper)).orElseThrow(() -> failure(helper, "the hangar data is unreadable"));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aMemberCraftsOneMoleFromFourPartsAndPaysNoFee(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MockPlayer crafter = member(helper, "Crafter");
                    BlockPos console = console(helper, crafter);
                    deposit(helper, crafter, RICH);

                    expectRefusedWith(
                            helper, told, crafter, console, "missing_parts", "assembling with no parts in the pack");
                    PodEntity mole = assemble(helper, before, crafter, console, "assembling a Mole");

                    expect(
                            helper,
                            PodComponents.registration(mole)
                                            .orElseThrow()
                                            .owner()
                                            .equals(charterOf(helper, crafter).id())
                                    && !Wrecks.isWreck(mole)
                                    && mole.hull() > 0f,
                            "the Mole works and belongs to the crafter's charter");
                    expect(
                            helper,
                            PARTS.stream().allMatch(part -> count(crafter.player(), part) == 0),
                            "the four parts are used up");
                    // The only change to the account is T06's bonus for a charter's first Mole: there is no
                    // registration fee.
                    expect(
                            helper,
                            balance(helper, crafter) == RICH + Transmission.Bonus.B1.amount(),
                            "a crafted Mole costs no money: the account holds %s",
                            balance(helper, crafter));
                    expect(
                            helper,
                            data(helper)
                                    .slotOf(crafter.player().getUUID())
                                    .map(HangarData.Slot::pod)
                                    .equals(Optional.of(mole.getUUID())),
                            "the Mole holds the crafter's one place");
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aSecondMoleIsRefusedWhileTheFirstWorksOrIsAWreckAndAnotherMemberStillCrafts(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MockPlayer director = member(helper, "Director");
                    MockPlayer crew = MockPlayers.join(helper, "Crew");
                    crew.player().setGameMode(GameType.SURVIVAL);
                    BlockPos console = console(helper, director);
                    stand(helper, crew, console);
                    // Crew joins the director's charter, so the two share one charter and one account.
                    CharterId charter = charterOf(helper, director).id();
                    expect(
                            helper,
                            Charters.apply(server(helper), crew.player().getUUID(), charter)
                                    .isEmpty(),
                            "applying");
                    expect(
                            helper,
                            Charters.approve(
                                            server(helper),
                                            director.player().getUUID(),
                                            crew.player().getUUID())
                                    .isEmpty(),
                            "approving");
                    deposit(helper, director, RICH);

                    PodEntity first = assemble(helper, before, director, console, "the director's Mole");
                    PARTS.forEach(part -> give(director.player(), part, 1));
                    expectRefusedWith(
                            helper, told, director, console, "mole_held", "a second Mole while the first works");
                    expect(
                            helper,
                            PARTS.stream().allMatch(part -> count(director.player(), part) == 1)
                                    && madeSince(helper, before).size() == 1,
                            "the refusal takes no part and makes no pod");

                    // The Mole is wrecked: the wreck keeps the place, so there is still no second one.
                    first.damageHull(first.maxHull());
                    expect(helper, Wrecks.isWreck(first), "the Mole is a wreck");
                    expectRefusedWith(
                            helper, told, director, console, "mole_wrecked", "a second Mole while a wreck exists");
                    expect(
                            helper,
                            madeSince(helper, before).size() == 1,
                            "no second pod was made for the wreck's owner");

                    // The place is the player's, not the charter's: the crew member crafts their own.
                    PodEntity second = assemble(helper, before, crew, console, "the crew member's Mole");
                    expect(
                            helper,
                            !second.getUUID().equals(first.getUUID())
                                    && data(helper)
                                            .slotOf(crew.player().getUUID())
                                            .isPresent(),
                            "each member has a place of their own");
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theFoundingMoleIsItsFoundersOneMole(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    MinecraftServer server = server(helper);
                    Hangar.onBuilt(server, Colony.placed(server).orElseThrow());
                    PodEntity derelict = HangarPods.derelict(server).orElseThrow();
                    MockPlayer founder = member(helper, "Founder");
                    BlockPos console = console(helper, founder);
                    PARTS.forEach(part -> give(founder.player(), part, 1));
                    for (var part : PARTS) {
                        expectDone(helper, TerminalParts.insert(founder.player(), console, part), "a repair part");
                    }
                    expect(
                            helper,
                            data(helper)
                                    .slotOf(founder.player().getUUID())
                                    .map(HangarData.Slot::pod)
                                    .equals(Optional.of(derelict.getUUID())),
                            "the founding Mole holds the place of the player who repaired it");
                    PARTS.forEach(part -> give(founder.player(), part, 1));
                    expectRefusedWith(helper, told, founder, console, "mole_held", "a crafted Mole for the founder");
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aWreckKeepsItsPartsAndCargoThroughTheTow(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MinecraftServer server = server(helper);
                    MockPlayer owner = member(helper, "Salvager");
                    BlockPos console = console(helper, owner);
                    deposit(helper, owner, RICH);
                    PodEntity mole = assemble(helper, before, owner, console, "the Mole");
                    CharterId charter = charterOf(helper, owner).id();
                    PodComponents.install(mole, ComponentItems.mint(server, ComponentTrack.DRILL, 2, charter));
                    expect(
                            helper,
                            mole.cargo().tryAdd(mole, new ItemStack(OreRegistry.item(OreType.IRONIUM), 3)),
                            "the hold takes ore");
                    bringHome(helper, mole);

                    mole.damageHull(mole.maxHull());
                    expect(helper, Wrecks.isWreck(mole), "the Mole is a wreck");
                    expect(
                            helper,
                            PodComponents.partOf(mole, ComponentTrack.DRILL)
                                                    .orElseThrow()
                                                    .tier()
                                            == 2
                                    && mole.cargo().count(OreType.IRONIUM) == 3,
                            "the wreck keeps its fitted drill and the hold's ore");
                    expect(helper, !mole.isRemoved(), "a wreck is never removed on its own");

                    expectDone(
                            helper,
                            act(owner.player(), console, HangarTerminal.RESTORE_WRECK),
                            "restoring a Mole wreck with no catalyst in the pack");
                    expect(
                            helper,
                            !Wrecks.isWreck(mole)
                                    && PodComponents.partOf(mole, ComponentTrack.DRILL)
                                                    .orElseThrow()
                                                    .tier()
                                            == 2
                                    && mole.cargo().count(OreType.IRONIUM) == 3,
                            "the restored Mole has its drill and ore, a fresh Mole would start bare");
                    PARTS.forEach(part -> give(owner.player(), part, 1));
                    expectRefusedWith(
                            helper, told, owner, console, "mole_held", "a spare Mole beside the restored one");
                    mole.discard();
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void scrappingAWreckAtTheHangarFreesThePlaceAndGivesBackItsContents(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MinecraftServer server = server(helper);
                    MockPlayer owner = member(helper, "Scrapper");
                    BlockPos console = console(helper, owner);
                    deposit(helper, owner, RICH);
                    PodEntity mole = assemble(helper, before, owner, console, "the Mole");
                    PodComponents.install(
                            mole,
                            ComponentItems.mint(
                                    server,
                                    ComponentTrack.DRILL,
                                    2,
                                    charterOf(helper, owner).id()));
                    mole.cargo().tryAdd(mole, new ItemStack(OreRegistry.item(OreType.IRONIUM), 3));
                    bringHome(helper, mole);

                    expectRefused(
                            helper,
                            act(owner.player(), console, HangarTerminal.SCRAP_WRECK),
                            "scrapping a Mole that works");
                    expect(helper, !mole.isRemoved(), "a Mole that works is not scrapped");

                    mole.damageHull(mole.maxHull());
                    long before$ = balance(helper, owner);
                    expectDone(helper, act(owner.player(), console, HangarTerminal.SCRAP_WRECK), "scrapping the wreck");
                    expect(
                            helper,
                            mole.isRemoved()
                                    && data(helper)
                                            .slotOf(owner.player().getUUID())
                                            .isEmpty(),
                            "the wreck is gone and the place is free");
                    expect(helper, balance(helper, owner) == before$, "scrapping costs nothing");
                    expect(
                            helper,
                            count(owner.player(), OreRegistry.item(OreType.IRONIUM)) == 3,
                            "the hold's ore is in the owner's pack");
                    List<ItemEntity> drops = helper.getLevel()
                            .getEntitiesOfClass(
                                    ItemEntity.class, new AABB(console).inflate(HangarTuning.DEFAULT.wreckRadius()));
                    expect(
                            helper,
                            drops.stream()
                                    .anyMatch(drop -> ComponentItems.trackOf(drop.getItem())
                                            .equals(Optional.of(ComponentTrack.DRILL))),
                            "the fitted drill dropped as an item");
                    drops.forEach(ItemEntity::discard);
                    PodEntity next = assemble(helper, before, owner, console, "a new Mole after the scrap");
                    next.discard();
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aWriteOffFreesThePlaceOnlyAfterOneDayAndDestroysTheWreck(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MinecraftServer server = server(helper);
                    MockPlayer owner = member(helper, "Writer");
                    BlockPos console = console(helper, owner);
                    long cost = HangarTuning.DEFAULT.writeOffCost();
                    deposit(helper, owner, RICH);
                    expectRefused(
                            helper, act(owner.player(), console, HangarTerminal.WRITE_OFF), "a write-off with no Mole");
                    PodEntity mole = assemble(helper, before, owner, console, "the Mole");
                    mole.cargo().tryAdd(mole, new ItemStack(OreRegistry.item(OreType.IRONIUM), 3));
                    expectRefused(
                            helper,
                            act(owner.player(), console, HangarTerminal.WRITE_OFF),
                            "a write-off of a Mole that works");
                    expect(
                            helper,
                            balance(helper, owner) == RICH + Transmission.Bonus.B1.amount(),
                            "a refusal costs nothing");

                    mole.damageHull(mole.maxHull());
                    long funds = balance(helper, owner);
                    expectDone(
                            helper,
                            act(owner.player(), console, HangarTerminal.WRITE_OFF),
                            "the first press, which only arms it");
                    HangarView armed =
                            HangarTerminal.view(server, owner.player(), Optional.of(charterOf(helper, owner)), console);
                    expect(
                            helper,
                            armed.mole() == HangarView.MoleState.WRECKED && armed.armedTicksLeft() > 0,
                            "the console's view shows the write-off armed, with %s ticks left",
                            armed.armedTicksLeft());
                    expect(
                            helper,
                            balance(helper, owner) == funds
                                    && data(helper)
                                            .slotOf(owner.player().getUUID())
                                            .orElseThrow()
                                            .writeOff()
                                            .isEmpty(),
                            "the first press takes nothing and files nothing");
                    expectDone(helper, act(owner.player(), console, HangarTerminal.WRITE_OFF), "writing off the wreck");
                    expect(helper, funds - balance(helper, owner) == cost, "the write-off costs $%s", cost);
                    long due = data(helper)
                            .slotOf(owner.player().getUUID())
                            .orElseThrow()
                            .writeOffDue()
                            .orElseThrow();
                    expect(
                            helper,
                            due - server.overworld().getGameTime() == HangarTuning.DEFAULT.writeOffTicks()
                                    && HangarTuning.DEFAULT.writeOffTicks() == ONE_DAY,
                            "the write-off takes one in-game day");
                    expectRefusedWith(
                            helper, told, owner, console, "mole_writing_off", "a Mole while the write-off waits");

                    Hangar.settleWriteOffs(server, due - 1);
                    expect(
                            helper,
                            !mole.isRemoved()
                                    && data(helper)
                                            .slotOf(owner.player().getUUID())
                                            .isPresent(),
                            "a tick before the day is out the place is not free and the wreck stands");
                    PARTS.forEach(part -> give(owner.player(), part, 1));
                    expectRefusedWith(
                            helper, told, owner, console, "mole_writing_off", "a Mole a tick before the day is out");

                    Hangar.settleWriteOffs(server, due);
                    expect(
                            helper,
                            mole.isRemoved()
                                    && data(helper)
                                            .slotOf(owner.player().getUUID())
                                            .isEmpty(),
                            "when the day is out the wreck is destroyed and the place is free");
                    expect(
                            helper,
                            helper.getLevel()
                                    .getEntitiesOfClass(
                                            ItemEntity.class,
                                            mole.getBoundingBox().inflate(4))
                                    .isEmpty(),
                            "a write-off destroys the parts and the cargo: nothing drops");
                    PodEntity next = assemble(helper, before, owner, console, "a new Mole after the write-off");
                    next.discard();
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aWriteOffFreesThePlaceOfAWreckThatIsNotLoaded(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MinecraftServer server = server(helper);
                    MockPlayer owner = member(helper, "Far Writer");
                    BlockPos console = console(helper, owner);
                    deposit(helper, owner, RICH);
                    PodEntity mole = assemble(helper, before, owner, console, "the Mole");
                    mole.damageHull(mole.maxHull());
                    fileWriteOff(helper, owner, console);
                    // The wreck is out of the world when the day is out, as in a chunk nobody has loaded.
                    mole.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                    UUID pod = mole.getUUID();
                    long due = data(helper)
                            .slotOf(owner.player().getUUID())
                            .orElseThrow()
                            .writeOffDue()
                            .orElseThrow();
                    Hangar.settleWriteOffs(server, due);
                    expect(
                            helper,
                            data(helper).slotOf(owner.player().getUUID()).isEmpty()
                                    && data(helper).state().condemned().contains(pod),
                            "the place is free and the wreck is condemned until it loads");
                    // It loads again (a stand-in for the chunk loading): it is destroyed and forgotten.
                    PodEntity back =
                            TestEntities.create(PodRegistry.POD, helper.getLevel(), EntitySpawnReason.TRIGGERED);
                    back.setUUID(pod);
                    back.setPos(helper.absoluteVec(new Vec3(5.5, 2, 5.5)));
                    helper.getLevel().addFreshEntity(back);
                    if (!back.isRemoved()) {
                        ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(back, helper.getLevel());
                    }
                    expect(
                            helper,
                            back.isRemoved() && data(helper).state().condemned().isEmpty(),
                            "the condemned wreck is destroyed when it loads");
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aWorldSavedBeforeTheOneMoleRuleLoadsAndGivesItsFoundingMoleToTheDirector(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    MinecraftServer server = server(helper);
                    Hangar.onBuilt(server, Colony.placed(server).orElseThrow());
                    PodEntity derelict = HangarPods.derelict(server).orElseThrow();
                    MockPlayer director = member(helper, "Old Director");
                    CharterId charter = charterOf(helper, director).id();

                    // The saved form of an older world: the founding Mole is repaired, and no field names a place.
                    CompoundTag saved = ((CompoundTag) HangarData.CODEC
                                    .encodeStart(NbtOps.INSTANCE, HangarData.get(server))
                                    .getOrThrow())
                            .copy();
                    saved.putBoolean("founded", true);
                    saved.put(
                            "founder",
                            CharterId.CODEC
                                    .encodeStart(NbtOps.INSTANCE, charter)
                                    .getOrThrow());
                    saved.putInt("version", 1);
                    saved.remove("moles");
                    saved.remove("condemned");
                    saved.remove("legacy_founding_place");
                    HangarData old =
                            HangarData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
                    expect(
                            helper,
                            old.isReadable()
                                    && old.state().moles().isEmpty()
                                    && old.state().condemned().isEmpty(),
                            "version 1 data, without the new fields, reads as no places and nothing condemned");
                    WorldData.replace(server, HangarData.TYPE, old);

                    ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(derelict, helper.getLevel());
                    expect(
                            helper,
                            data(helper)
                                    .slotOf(director.player().getUUID())
                                    .map(HangarData.Slot::pod)
                                    .equals(Optional.of(derelict.getUUID())),
                            "the founding Mole of an older world is given to the founding charter's Director when it loads");
                    ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(derelict, helper.getLevel());
                    expect(helper, data(helper).state().moles().size() == 1, "loading it again gives no second place");
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    /** Makes {@code crew} a member of the charter of {@code director}, so the two share a charter and an account. */
    private static void joinCharter(GameTestHelper helper, MockPlayer director, MockPlayer crew) {
        CharterId charter = charterOf(helper, director).id();
        expect(
                helper,
                Charters.apply(server(helper), crew.player().getUUID(), charter).isEmpty(),
                "applying");
        expect(
                helper,
                Charters.approve(
                                server(helper),
                                director.player().getUUID(),
                                crew.player().getUUID())
                        .isEmpty(),
                "approving");
    }

    private static void wreckAt(GameTestHelper helper, PodEntity pod, double x, double z) {
        pod.setPos(helper.absoluteVec(new Vec3(x, 2, z)));
        pod.damageHull(pod.maxHull());
    }

    private static MockPlayer crewOf(GameTestHelper helper, MockPlayer director, BlockPos console, String name) {
        MockPlayer crew = MockPlayers.join(helper, name);
        crew.player().setGameMode(GameType.SURVIVAL);
        stand(helper, crew, console);
        joinCharter(helper, director, crew);
        return crew;
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aCharterMateCannotScrapAWreckThatHoldsAnotherMembersPlaceButTheDirectorCan(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MockPlayer director = member(helper, "Scrap Director");
                    BlockPos console = console(helper, director);
                    deposit(helper, director, RICH);
                    MockPlayer crew = crewOf(helper, director, console, "Scrap Crew");
                    PodEntity directors = assemble(helper, before, director, console, "the Director's Mole");
                    wreckAt(helper, directors, 3.5, 3.5);

                    expectRefused(
                            helper,
                            act(crew.player(), console, HangarTerminal.SCRAP_WRECK),
                            "a charter-mate scrapping the Director's wreck");
                    told.add(new Told(crew, "not_your_place", "the charter-mate's scrap"));
                    expect(
                            helper,
                            !directors.isRemoved()
                                    && data(helper)
                                            .slotOf(director.player().getUUID())
                                            .isPresent(),
                            "the refused scrap leaves the wreck and the place");

                    PodEntity crews = assemble(helper, before, crew, console, "the crew member's Mole");
                    wreckAt(helper, crews, 7.5, 7.5);
                    // Both wrecks lie in reach and the Director's is nearer: the crew member's scrap takes their own.
                    expectDone(helper, act(crew.player(), console, HangarTerminal.SCRAP_WRECK), "scrapping one's own");
                    expect(
                            helper,
                            crews.isRemoved()
                                    && !directors.isRemoved()
                                    && data(helper)
                                            .slotOf(crew.player().getUUID())
                                            .isEmpty(),
                            "the caller's own wreck is chosen when a charter-mate's is nearer");

                    // The Director may scrap a member's wreck as a fallback.
                    PodEntity crews2 = assemble(helper, before, crew, console, "the crew member's second Mole");
                    wreckAt(helper, crews2, 7.5, 7.5);
                    directors.setPos(helper.absoluteVec(new Vec3(5.5, 2, 5.5)));
                    Wrecks.restore(directors, directors.maxHull());
                    expect(helper, !Wrecks.isWreck(directors), "the Director's Mole works again");
                    expectDone(
                            helper,
                            act(director.player(), console, HangarTerminal.SCRAP_WRECK),
                            "the Director scrapping a member's wreck");
                    expect(helper, crews2.isRemoved(), "the Director scrapped the member's wreck");
                    directors.discard();
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aNearerWreckNobodyOwnsDoesNotBlockScrappingYourOwn(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MockPlayer owner = member(helper, "Unowned Neighbour");
                    BlockPos console = console(helper, owner);
                    deposit(helper, owner, RICH);
                    PodEntity unowned = pod(helper, Optional.empty(), true);
                    unowned.setPos(helper.absoluteVec(new Vec3(2.5, 2, 3.5)));
                    PodEntity mine = assemble(helper, before, owner, console, "the Mole");
                    wreckAt(helper, mine, 7.5, 7.5);
                    expectDone(helper, act(owner.player(), console, HangarTerminal.SCRAP_WRECK), "scrapping one's own");
                    expect(
                            helper,
                            mine.isRemoved() && !unowned.isRemoved(),
                            "the wreck nobody owns is left, and the owned one is scrapped");
                    expectRefused(
                            helper,
                            act(owner.player(), console, HangarTerminal.SCRAP_WRECK),
                            "scrapping only a wreck nobody owns");
                    told.add(new Told(owner, "no_scrappable_wreck", "scrapping an unowned wreck"));
                    unowned.discard();
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    private static void stash(PodEntity pod, UUID pilot, ItemStack stack) {
        Versioned.modifyOrThrow(pod, WreckRegistry.PACK, pack -> {
            List<WreckPack.Entry> entries = new ArrayList<>(pack.entries());
            entries.add(new WreckPack.Entry(pilot, stack));
            return new WreckPack(entries);
        });
    }

    private static int packed(PodEntity pod, Item item) {
        return Versioned.readable(pod, WreckRegistry.PACK).orElseThrow().entries().stream()
                .filter(entry -> entry.stack().is(item))
                .mapToInt(entry -> entry.stack().getCount())
                .sum();
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aDyingPilotsPackStaysInTheWreckForThemAndANobodyHomeRestoreWaits(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MockPlayer director = member(helper, "Pack Director");
                    BlockPos console = console(helper, director);
                    deposit(helper, director, RICH);
                    MockPlayer pilot = crewOf(helper, director, console, "Pack Pilot");
                    MockPlayer mate = director;
                    MockPlayer stranger = member(helper, "Pack Stranger");
                    stand(helper, stranger, console);
                    PodEntity mole = assemble(helper, before, pilot, console, "the Mole");
                    bringHome(helper, mole);
                    pilot.player().getInventory().add(new ItemStack(Items.DIAMOND, 5));
                    expect(helper, pilot.player().startRiding(mole, true, false), "the pilot boards their Mole");

                    mole.damageHull(mole.maxHull());
                    expect(helper, Wrecks.isWreck(mole), "the Mole is a wreck");
                    WreckPack saved =
                            Versioned.readable(mole, WreckRegistry.PACK).orElseThrow();
                    expect(
                            helper,
                            saved.entries().size() == 1
                                    && saved.entries()
                                            .getFirst()
                                            .pilot()
                                            .equals(pilot.player().getUUID())
                                    && packed(mole, Items.DIAMOND) == 5,
                            "the wreck holds the pilot's 5 diamonds, under the pilot's name");
                    expect(
                            helper,
                            count(pilot.player(), Items.DIAMOND) == 0
                                    && helper.getLevel()
                                            .getEntitiesOfClass(
                                                    ItemEntity.class,
                                                    mole.getBoundingBox().inflate(8))
                                            .isEmpty(),
                            "the crew die with empty hands and nothing drops where they fell");

                    Wrecks.salvage(stranger.player(), mole);
                    Wrecks.salvage(mate.player(), mole);
                    expect(
                            helper,
                            count(stranger.player(), Items.DIAMOND) == 0
                                    && count(mate.player(), Items.DIAMOND) == 0
                                    && packed(mole, Items.DIAMOND) == 5,
                            "no one else salvaging the wreck takes the pilot's pack");

                    // The pilot is dead and so is the place holder (the same player): the pack has no one to go to.
                    expectRefused(
                            helper,
                            act(mate.player(), console, HangarTerminal.RESTORE_WRECK),
                            "a charter-mate restoring a wreck whose pilot is away");
                    told.add(new Told(mate, "pack_owner_away", "the restore that waits"));
                    expect(
                            helper,
                            Wrecks.isWreck(mole) && packed(mole, Items.DIAMOND) == 5,
                            "the refused restore leaves the wreck and its pack");

                    // A scrap frees the place and drops what no one could be handed.
                    expectDone(helper, act(mate.player(), console, HangarTerminal.SCRAP_WRECK), "scrapping it");
                    List<ItemEntity> drops = helper.getLevel()
                            .getEntitiesOfClass(
                                    ItemEntity.class, mole.getBoundingBox().inflate(8));
                    expect(
                            helper,
                            mole.isRemoved()
                                    && count(mate.player(), Items.DIAMOND) == 0
                                    && drops.stream()
                                            .anyMatch(drop -> drop.getItem().is(Items.DIAMOND)),
                            "a scrap drops the pack that no one could be handed, and gives the mate none of it");
                    drops.forEach(ItemEntity::discard);
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aRestoreGivesEachPlayerTheirOwnStacksAndTheHolderThoseOfAnAbsentPilot(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MockPlayer holder = member(helper, "Pack Holder");
                    BlockPos console = console(helper, holder);
                    deposit(helper, holder, RICH);
                    MockPlayer pilot = crewOf(helper, holder, console, "Pack Rider");
                    MockPlayer restorer = crewOf(helper, holder, console, "Pack Restorer");
                    PodEntity mole = assemble(helper, before, holder, console, "the holder's Mole");
                    bringHome(helper, mole);
                    stash(mole, pilot.player().getUUID(), new ItemStack(Items.DIAMOND, 3));
                    stash(mole, holder.player().getUUID(), new ItemStack(Items.EMERALD, 2));
                    stash(mole, UUID.randomUUID(), new ItemStack(Items.COAL, 4));
                    mole.damageHull(mole.maxHull());

                    Wrecks.salvage(pilot.player(), mole);
                    expect(
                            helper,
                            count(pilot.player(), Items.DIAMOND) == 3 && packed(mole, Items.DIAMOND) == 0,
                            "a salvage gives a player their own stacks");
                    stash(mole, pilot.player().getUUID(), new ItemStack(Items.DIAMOND, 3));

                    expectDone(helper, act(restorer.player(), console, HangarTerminal.RESTORE_WRECK), "restoring");
                    expect(
                            helper,
                            count(pilot.player(), Items.DIAMOND) == 6
                                    && count(holder.player(), Items.EMERALD) == 2
                                    && count(holder.player(), Items.COAL) == 4
                                    && count(restorer.player(), Items.DIAMOND) == 0
                                    && count(restorer.player(), Items.EMERALD) == 0
                                    && count(restorer.player(), Items.COAL) == 0,
                            "each stack goes to its pilot, a gone pilot's to the holder, and none to the restorer");
                    expect(
                            helper,
                            Versioned.readable(mole, WreckRegistry.PACK)
                                    .orElseThrow()
                                    .entries()
                                    .isEmpty(),
                            "the restored Mole holds no pack");
                    mole.discard();
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void anUnreadableWreckPackIsKeptAndNeverDestroyed(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MockPlayer pilot = member(helper, "Unreadable Pilot");
                    BlockPos console = console(helper, pilot);
                    deposit(helper, pilot, RICH);
                    PodEntity mole = assemble(helper, before, pilot, console, "the Mole");
                    bringHome(helper, mole);
                    CompoundTag raw = new CompoundTag();
                    raw.putString("future", "pack");
                    mole.setAttached(WreckRegistry.PACK, new Versioned.Unreadable<>(raw));
                    pilot.player().getInventory().add(new ItemStack(Items.DIAMOND, 5));
                    expect(helper, pilot.player().startRiding(mole, true, false), "the pilot boards");

                    mole.damageHull(mole.maxHull());
                    expect(
                            helper,
                            mole.getAttached(WreckRegistry.PACK) instanceof Versioned.Unreadable<WreckPack> kept
                                    && kept.raw().equals(raw),
                            "the unreadable saved pack is written back as it was read");
                    expect(
                            helper,
                            count(pilot.player(), Items.DIAMOND) == 0
                                    && helper
                                            .getLevel()
                                            .getEntitiesOfClass(
                                                    ItemEntity.class,
                                                    mole.getBoundingBox().inflate(8))
                                            .stream()
                                            .anyMatch(drop -> drop.getItem().is(Items.DIAMOND)),
                            "the pilot's pack drops as it did before, since nothing may be written over the saved one");
                    helper.getLevel()
                            .getEntitiesOfClass(
                                    ItemEntity.class, mole.getBoundingBox().inflate(8))
                            .forEach(ItemEntity::discard);
                    MockPlayer other = crewOf(helper, pilot, console, "Unreadable Mate");
                    Wrecks.salvage(other.player(), mole);
                    expect(
                            helper,
                            Wrecks.returnPack(
                                                    server(helper),
                                                    mole,
                                                    Optional.of(other.player().getUUID()))
                                            .isEmpty()
                                    && mole.getAttached(WreckRegistry.PACK) instanceof Versioned.Unreadable<WreckPack>,
                            "salvage and return leave an unreadable pack alone and do not throw");
                    mole.discard();
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aWriteOffOfTheFirstBuildsShapeStillDecodes(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        UUID pod = UUID.randomUUID();
        CompoundTag slot = new CompoundTag();
        slot.put("player", UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, player).getOrThrow());
        slot.put("pod", UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, pod).getOrThrow());
        slot.putBoolean("wrecked", true);
        slot.putLong("write_off_due", 4_242L);
        CompoundTag saved = ((CompoundTag) HangarData.CODEC
                        .encodeStart(NbtOps.INSTANCE, new HangarData())
                        .getOrThrow())
                .copy();
        saved.putInt("version", 1);
        ListTag moles = new ListTag();
        moles.add(slot);
        saved.put("moles", moles);
        HangarData old = HangarData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        HangarData.Slot read = old.slotOf(player).orElseThrow();
        expect(
                helper,
                old.isReadable()
                        && read.writeOffDue().equals(Optional.of(4_242L))
                        && read.writeOff().orElseThrow().payer().isEmpty()
                        && read.writeOff().orElseThrow().paid() == 0,
                "a bare due time reads as a write-off with nobody to refund, it read %s",
                read);
        Tag again = HangarData.CODEC.encodeStart(NbtOps.INSTANCE, old).getOrThrow();
        expect(
                helper,
                HangarData.CODEC
                        .parse(NbtOps.INSTANCE, again)
                        .getOrThrow()
                        .slotOf(player)
                        .orElseThrow()
                        .equals(read),
                "it is saved in the current shape and reads back the same");
        helper.succeed();
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aWriteOffThatFailsToDestroyTheWreckLeavesThePlaceHeld(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MinecraftServer server = server(helper);
                    MockPlayer owner = member(helper, "Failing Writer");
                    BlockPos console = console(helper, owner);
                    deposit(helper, owner, RICH);
                    PodEntity mole = assemble(helper, before, owner, console, "the Mole");
                    mole.damageHull(mole.maxHull());
                    fileWriteOff(helper, owner, console);
                    long due = data(helper)
                            .slotOf(owner.player().getUUID())
                            .orElseThrow()
                            .writeOffDue()
                            .orElseThrow();
                    FAILING.add(mole.getUUID());
                    try {
                        Hangar.settleWriteOffs(server, due);
                    } finally {
                        FAILING.clear();
                    }
                    expect(
                            helper,
                            data(helper).slotOf(owner.player().getUUID()).isPresent(),
                            "a failed destruction leaves the place held, it was freed first");
                    // The next check finds the wreck gone from the world, condemns it, and frees the place.
                    Hangar.settleWriteOffs(server, due);
                    expect(
                            helper,
                            data(helper).slotOf(owner.player().getUUID()).isEmpty(),
                            "the next check finishes the write-off");
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void restoringAWreckAfterAWriteOffIsFiledCancelsItAndRefundsIt(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MinecraftServer server = server(helper);
                    MockPlayer owner = member(helper, "Changing Mind");
                    BlockPos console = console(helper, owner);
                    deposit(helper, owner, RICH);
                    PodEntity mole = assemble(helper, before, owner, console, "the Mole");
                    bringHome(helper, mole);
                    mole.damageHull(mole.maxHull());
                    long funds = balance(helper, owner);
                    fileWriteOff(helper, owner, console);
                    long due = data(helper)
                            .slotOf(owner.player().getUUID())
                            .orElseThrow()
                            .writeOffDue()
                            .orElseThrow();
                    expectDone(
                            helper, act(owner.player(), console, HangarTerminal.RESTORE_WRECK), "restoring the wreck");
                    expect(
                            helper,
                            balance(helper, owner)
                                    == funds
                                            - HangarTuning.DEFAULT
                                                    .restoreCost(Chassis.MOLE)
                                                    .money(),
                            "the write-off is refunded and the restore is paid: the account holds %s",
                            balance(helper, owner));
                    expect(
                            helper,
                            data(helper)
                                    .slotOf(owner.player().getUUID())
                                    .orElseThrow()
                                    .writeOff()
                                    .isEmpty(),
                            "the filing is cancelled");
                    Hangar.settleWriteOffs(server, due);
                    expect(
                            helper,
                            !mole.isRemoved() && !Wrecks.isWreck(mole),
                            "the day running out destroys nothing: the Mole works");
                    mole.discard();
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aMoleRemovedForGoodFreesItsPlaceAndAnOperatorCanFreeOne(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MinecraftServer server = server(helper);
                    MockPlayer owner = member(helper, "LostPilot");
                    BlockPos console = console(helper, owner);
                    PodEntity lost = assemble(helper, before, owner, console, "the Mole");
                    lost.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                    expect(
                            helper,
                            data(helper).slotOf(owner.player().getUUID()).isPresent(),
                            "a Mole unloaded with its chunk keeps its place");
                    PodEntity killed =
                            assemble(helper, before, member(helper, "Killed Pilot"), console, "another Mole");
                    killed.discard();
                    expect(
                            helper,
                            data(helper).slotHolding(killed.getUUID()).isEmpty(),
                            "a Mole discarded for good frees its place");

                    server.getCommands()
                            .performPrefixedCommand(
                                    server.createCommandSourceStack(), "deepcharter hangar release LostPilot");
                    expect(
                            helper,
                            data(helper).slotOf(owner.player().getUUID()).isEmpty(),
                            "the operator command frees the place");
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aLegacyWreckedFoundingMoleGetsItsDirectorsPlaceAndADirectorWithOneKeepsIt(GameTestHelper helper) {
        List<Told> told = new ArrayList<>();
        inTheHangar(
                helper,
                before -> withFreshWorld(helper, () -> {
                    repairTheConsole(helper);
                    MinecraftServer server = server(helper);
                    Hangar.onBuilt(server, Colony.placed(server).orElseThrow());
                    PodEntity derelict = HangarPods.derelict(server).orElseThrow();
                    MockPlayer director = member(helper, "Wrecked Director");
                    // The derelict is still a wreck: a legacy founding Mole that was wrecked before the rule.
                    expect(helper, Wrecks.isWreck(derelict), "the founding Mole is a wreck");
                    WorldData.replace(
                            server,
                            HangarData.TYPE,
                            legacyHangar(server, charterOf(helper, director).id()));
                    ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(derelict, helper.getLevel());
                    HangarData.Slot slot =
                            data(helper).slotOf(director.player().getUUID()).orElseThrow();
                    expect(
                            helper,
                            slot.wrecked() && slot.pod().equals(derelict.getUUID()),
                            "the wrecked founding Mole holds the Director's place, recorded as a wreck");
                    expect(helper, !data(helper).state().legacyFoundingPlace(), "the migration ran once");

                    // A Director who holds a place already keeps it, and the migration still settles.
                    MockPlayer other = member(helper, "Placed Director");
                    WorldData.replace(
                            server,
                            HangarData.TYPE,
                            legacyHangar(server, charterOf(helper, other).id()));
                    BlockPos console = console(helper, other);
                    PodEntity theirs = assemble(helper, before, other, console, "the Director's own Mole");
                    ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(derelict, helper.getLevel());
                    expect(
                            helper,
                            data(helper)
                                            .slotOf(other.player().getUUID())
                                            .orElseThrow()
                                            .pod()
                                            .equals(theirs.getUUID())
                                    && !data(helper).state().legacyFoundingPlace(),
                            "a Director with a place keeps it, and the migration settles");
                    theirs.discard();
                    clearFloor(helper);
                    finish(helper, told);
                }));
    }

    /** The saved form of an older world: a founded hangar of version 1 whose founder is {@code charter}. */
    private static HangarData legacyHangar(MinecraftServer server, CharterId charter) {
        CompoundTag saved = ((CompoundTag) HangarData.CODEC
                        .encodeStart(NbtOps.INSTANCE, HangarData.get(server))
                        .getOrThrow())
                .copy();
        saved.putBoolean("founded", true);
        saved.put(
                "founder", CharterId.CODEC.encodeStart(NbtOps.INSTANCE, charter).getOrThrow());
        saved.putInt("version", 1);
        saved.remove("moles");
        saved.remove("condemned");
        saved.remove("legacy_founding_place");
        return HangarData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
    }
}
