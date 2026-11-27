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
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.repairTheConsole;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.server;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.stand;
import static io.github.pkeppeler.deepcharter.test.FoundingMoleHangarTest.withFreshWorld;
import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.hangar.Hangar;
import io.github.pkeppeler.deepcharter.hangar.HangarData;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
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
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #1595 (ADR 0117): each charter member crafts one Mole from four parts and pays no fee, a wreck keeps its
 * owner's place and what it holds, only scrapping at the hangar or a write-off a day later frees the place, and a world saved before
 * the rule still loads.
 *
 * <p>Every test runs in a fresh hangar record inside the hangar's tick, as {@link FoundingMoleHangarTest} does.
 */
public class MoleSlotTest {
    private static final int ONE_DAY = 24_000;
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
                    give(owner.player(), OreRegistry.item(HangarTuning.DEFAULT.catalyst()), 2);
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
                            helper, act(owner.player(), console, HangarTerminal.RESTORE_WRECK), "restoring the wreck");
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
                    expectDone(helper, act(owner.player(), console, HangarTerminal.WRITE_OFF), "writing off the wreck");
                    // The wreck is out of the world when the day is out, as in a chunk nobody has loaded.
                    mole.discard();
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
                    saved.remove("moles");
                    saved.remove("condemned");
                    HangarData old =
                            HangarData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
                    expect(
                            helper,
                            old.isReadable()
                                    && old.state().moles().isEmpty()
                                    && old.state().condemned().isEmpty(),
                            "data without the new fields reads as no places and nothing condemned");
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
}
