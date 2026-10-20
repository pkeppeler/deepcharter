package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.market.ProcessorView;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #68: "Sell All" at the ore processor credits exactly the {@link OreType} values, from the cargo of a pod
 * parked at it and from the player's inventory; a player on no charter, a full account and unreadable cargo are refused and
 * nothing is consumed.
 *
 * <p>The repair state is one per world, so each test swaps in a fresh one with the processor repaired, and does all its work
 * inside its first tick, as the terminal framework tests do.
 */
public class OreProcessorTest {
    private static final String UNREADABLE_CARGO = "deepcharter.market.refusal.unreadable_cargo";
    private static final String NO_POD = "deepcharter.market.refusal.no_pod";
    private static final String NOTHING_TO_SELL = "deepcharter.market.refusal.nothing_to_sell";

    private static void withProcessorOnline(MinecraftServer server, Runnable body) {
        RepairState fresh = new RepairState();
        WorldData.with(server, RepairState.TYPE, fresh, () -> {
            for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
                type.parts().forEach(part -> fresh.insert(type, part));
            }
            body.run();
        });
    }

    private static MockPlayer player(GameTestHelper helper, String name, boolean onCharter) {
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.player().setGameMode(GameType.SURVIVAL);
        MinecraftServer server = helper.getLevel().getServer();
        if (onCharter
                && Charters.found(
                                server,
                                mock.player().getUUID(),
                                name + " " + UUID.randomUUID().toString().substring(0, 8))
                        .isPresent()) {
            throw helper.assertionException("founding a charter for %s should succeed", name);
        }
        return mock;
    }

    /** The processor at a fixed place in the test, and the player standing two blocks from it. */
    private static BlockPos processorFor(GameTestHelper helper, MockPlayer mock) {
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState());
        BlockPos pos = helper.absolutePos(relative);
        Vec3 centre = Vec3.atCenterOf(pos);
        mock.teleportTo(
                helper.getLevel(),
                new Vec3(centre.x + 2, centre.y - mock.player().getEyeHeight(), centre.z),
                0,
                0);
        return pos;
    }

    private static PodEntity podAt(GameTestHelper helper, Vec3 at, OreType... ores) {
        ServerLevel level = helper.getLevel();
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(at);
        level.addFreshEntity(pod);
        for (OreType ore : ores) {
            pod.cargo().tryAdd(pod, OreRegistry.stack(ore));
        }
        return pod;
    }

    private static Vec3 beside(BlockPos processor, double distance) {
        return Vec3.atCenterOf(processor).add(0, 0, distance);
    }

    private static void carry(ServerPlayer player, OreType... ores) {
        for (OreType ore : ores) {
            player.getInventory().add(OreRegistry.stack(ore));
        }
    }

    private static long balance(MinecraftServer server, ServerPlayer player) {
        return Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().account();
    }

    private static int ores(ServerPlayer player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (OreRegistry.typeOf(player.getInventory().getItem(slot)).isPresent()) {
                count += player.getInventory().getItem(slot).getCount();
            }
        }
        return count;
    }

    private static long worth(OreType... ores) {
        long total = 0;
        for (OreType ore : ores) {
            total += ore.value();
        }
        return total;
    }

    private static void expectDone(GameTestHelper helper, Optional<TerminalRefusal> refusal, String what) {
        if (refusal.isPresent()) {
            throw helper.assertionException("%s should succeed, was refused: %s", what, refusal.get());
        }
    }

    private static void expectRefused(
            GameTestHelper helper, TerminalRefusal expected, Optional<TerminalRefusal> actual, String what) {
        if (!actual.equals(Optional.of(expected))) {
            throw helper.assertionException("%s should be refused with %s, got %s", what, expected, actual);
        }
    }

    private static void expectKey(GameTestHelper helper, String key, Optional<Component> refusal, String what) {
        if (refusal.isEmpty()
                || !(refusal.get().getContents() instanceof TranslatableContents contents)
                || !contents.getKey().equals(key)) {
            throw helper.assertionException("%s should be refused with %s, got %s", what, key, refusal);
        }
    }

    private static TerminalAction.Context context(MinecraftServer server, ServerPlayer player, BlockPos pos) {
        return new TerminalAction.Context(
                server,
                player,
                Charters.charterOfOrThrow(server, player.getUUID()),
                TerminalTypes.ORE_PROCESSOR,
                pos,
                new CompoundTag());
    }

    @GameTest
    public void sellAllCreditsTheExactValueFromCargoAndInventory(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Seller", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity near = podAt(helper, beside(processor, 4), OreType.IRONIUM, OreType.GOLDIUM, OreType.EINSTEINIUM);
            PodEntity far =
                    podAt(helper, beside(processor, TerminalTuning.DEFAULT.parkedRadius() + 3), OreType.PLATINIUM);
            carry(player, OreType.BRONZIUM, OreType.CICATRIUM, OreType.SILVERIUM);
            try {
                long before = balance(server, player);

                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()),
                        "selling the cargo");
                long cargo = worth(OreType.IRONIUM, OreType.GOLDIUM, OreType.EINSTEINIUM);
                if (balance(server, player) != before + cargo
                        || near.cargoUsed() != 0
                        || !near.cargo().entries().isEmpty()) {
                    throw helper.assertionException(
                            "selling the cargo should credit %s and empty the bay: balance %s -> %s, bay %s",
                            cargo, before, balance(server, player), near.cargo().entries());
                }
                if (far.cargoUsed() != 1 || ores(player) != 3) {
                    throw helper.assertionException(
                            "a pod out of the radius and the inventory must be left alone by a cargo sale");
                }

                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.SELL_INVENTORY, new CompoundTag()),
                        "selling the inventory");
                long inventory = worth(OreType.BRONZIUM, OreType.CICATRIUM, OreType.SILVERIUM);
                if (balance(server, player) != before + cargo + inventory || ores(player) != 0) {
                    throw helper.assertionException(
                            "selling the inventory should credit %s and take every ore: balance %s -> %s, %s ore left",
                            inventory, before + cargo, balance(server, player), ores(player));
                }
                if (far.cargoUsed() != 1) {
                    throw helper.assertionException("a pod out of the radius must keep its cargo");
                }
            } finally {
                near.discard();
                far.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void everyOreSellsForItsOriginalDollarValue(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Appraiser", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            for (OreType ore : OreType.values()) {
                carry(player, ore);
                long before = balance(server, player);
                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.SELL_INVENTORY, new CompoundTag()),
                        "selling " + ore);
                if (balance(server, player) != before + ore.value()) {
                    throw helper.assertionException(
                            "%s should sell for %s, balance went %s -> %s",
                            ore, ore.value(), before, balance(server, player));
                }
            }
        });
        helper.succeed();
    }

    @GameTest
    public void aPlayerOnNoCharterIsRefusedAndKeepsTheOre(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Drifter", false);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity pod = podAt(helper, beside(processor, 4), OreType.GOLDIUM);
            carry(player, OreType.IRONIUM);
            try {
                expectRefused(
                        helper,
                        TerminalRefusal.NOT_ON_A_CHARTER,
                        Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()),
                        "a cargo sale by a player on no charter");
                expectRefused(
                        helper,
                        TerminalRefusal.NOT_ON_A_CHARTER,
                        Terminals.act(player, processor, OreProcessor.SELL_INVENTORY, new CompoundTag()),
                        "an inventory sale by a player on no charter");
                if (pod.cargoUsed() != 1 || ores(player) != 1) {
                    throw helper.assertionException("a refused sale must consume nothing");
                }
            } finally {
                pod.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void aFullAccountRefusesTheSaleAndConsumesNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Hoarder", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity pod = podAt(helper, beside(processor, 4), OreType.GOLDIUM);
            carry(player, OreType.IRONIUM);
            try {
                Charters.deposit(
                        server,
                        Charters.charterOfOrThrow(server, player.getUUID())
                                .orElseThrow()
                                .id(),
                        Long.MAX_VALUE - 10);
                long before = balance(server, player);
                expectRefused(
                        helper,
                        TerminalRefusal.ACTION_REFUSED,
                        Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()),
                        "a cargo sale into a full account");
                expectRefused(
                        helper,
                        TerminalRefusal.ACTION_REFUSED,
                        Terminals.act(player, processor, OreProcessor.SELL_INVENTORY, new CompoundTag()),
                        "an inventory sale into a full account");
                if (balance(server, player) != before || pod.cargoUsed() != 1 || ores(player) != 1) {
                    throw helper.assertionException(
                            "a full account must keep the ore and the balance: balance %s -> %s, cargo %s, carried %s",
                            before, balance(server, player), pod.cargoUsed(), ores(player));
                }
            } finally {
                pod.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void unreadableCargoRefusesWithoutThrowing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Reader", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity pod = podAt(helper, beside(processor, 4), OreType.GOLDIUM);
            try {
                TagValueOutput output =
                        TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                pod.saveWithoutId(output);
                CompoundTag tag = output.buildResult();
                tag.putInt("cargo_version", 99);
                pod.cargo().load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), pod);
                if (pod.cargo().isReadable()) {
                    throw helper.assertionException("the test needs unreadable cargo");
                }
                long before = balance(server, player);
                expectKey(
                        helper,
                        UNREADABLE_CARGO,
                        OreProcessor.sellCargo(context(server, player, processor)),
                        "selling unreadable cargo");
                expectRefused(
                        helper,
                        TerminalRefusal.ACTION_REFUSED,
                        Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()),
                        "selling unreadable cargo through the terminal");
                if (balance(server, player) != before) {
                    throw helper.assertionException("unreadable cargo must credit nothing");
                }
            } finally {
                pod.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void nothingToSellAndNoPodAreRefused(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Empty", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            expectKey(
                    helper,
                    NO_POD,
                    OreProcessor.sellCargo(context(server, player, processor)),
                    "selling cargo with no pod parked");
            expectKey(
                    helper,
                    NOTHING_TO_SELL,
                    OreProcessor.sellInventory(context(server, player, processor)),
                    "selling an empty inventory");
            PodEntity pod = podAt(helper, beside(processor, 4));
            try {
                expectKey(
                        helper,
                        NOTHING_TO_SELL,
                        OreProcessor.sellCargo(context(server, player, processor)),
                        "selling an empty bay");
            } finally {
                pod.discard();
            }
            if (balance(server, player) != 0) {
                throw helper.assertionException("nothing sold must credit nothing");
            }
        });
        helper.succeed();
    }

    /** Registers {@code pod} to the charter of {@code owner}. */
    private static void register(MinecraftServer server, PodEntity pod, ServerPlayer owner) {
        PodComponents.register(
                pod,
                Charters.charterOfOrThrow(server, owner.getUUID()).orElseThrow().id());
    }

    @GameTest
    public void anotherChartersPodIsNeitherSoldNorDumped(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Thief", true);
            MockPlayer rival = player(helper, "Rival", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity theirs = podAt(helper, beside(processor, 4), OreType.EINSTEINIUM);
            register(server, theirs, rival.player());
            try {
                long before = balance(server, player);
                expectKey(
                        helper,
                        NO_POD,
                        OreProcessor.sellCargo(context(server, player, processor)),
                        "selling the cargo of another charter's pod");
                PodEntity mine = podAt(helper, beside(processor, -4), OreType.IRONIUM);
                register(server, mine, player);
                try {
                    expectDone(
                            helper,
                            Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()),
                            "selling beside a rival's pod");
                    if (balance(server, player) != before + OreType.IRONIUM.value()
                            || mine.cargoUsed() != 0
                            || theirs.cargoUsed() != 1
                            || theirs.cargo().entries().size() != 1) {
                        throw helper.assertionException(
                                "only the own pod may be sold and dumped: balance %s -> %s, rival cargo %s",
                                before, balance(server, player), theirs.cargo().entries());
                    }
                } finally {
                    mine.discard();
                }
            } finally {
                theirs.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void anUnownedPodAndADormantOwnersPodAreSold(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Salvager", true);
            MockPlayer lapsed = player(helper, "Lapsed", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity unowned = podAt(helper, beside(processor, 4), OreType.IRONIUM);
            PodEntity dormant = podAt(helper, beside(processor, -4), OreType.GOLDIUM);
            register(server, dormant, lapsed.player());
            try {
                Optional<CharterRefusal> left =
                        Charters.leave(server, lapsed.player().getUUID());
                if (left.isPresent()) {
                    throw helper.assertionException("leaving should succeed, was refused: %s", left.get());
                }
                long before = balance(server, player);
                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()),
                        "selling unowned and dormant pods");
                if (balance(server, player) != before + worth(OreType.IRONIUM, OreType.GOLDIUM)
                        || unowned.cargoUsed() != 0
                        || dormant.cargoUsed() != 0) {
                    throw helper.assertionException(
                            "an unowned pod and a dormant owner's pod are anyone's: balance %s -> %s",
                            before, balance(server, player));
                }
            } finally {
                unowned.discard();
                dormant.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void aPodWithUnreadableComponentsIsNotSold(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Doubter", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity pod = podAt(helper, beside(processor, 4), OreType.GOLDIUM);
            pod.setAttached(PodComponents.STATE, new Versioned.Unreadable<PodComponents.State>(new CompoundTag()));
            try {
                expectKey(
                        helper,
                        NO_POD,
                        OreProcessor.sellCargo(context(server, player, processor)),
                        "selling a pod whose owner is unknown");
                if (pod.cargoUsed() != 1 || balance(server, player) != 0) {
                    throw helper.assertionException("a pod with unreadable components must keep its cargo");
                }
            } finally {
                pod.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void anUnreadableCargoIsSkippedAndTheOtherPodsAreSold(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Sorter", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity good = podAt(helper, beside(processor, 4), OreType.SILVERIUM);
            PodEntity bad = podAt(helper, beside(processor, -4), OreType.GOLDIUM);
            LogCapture log = LogCapture.start(bad.getUUID().toString());
            try {
                TagValueOutput output =
                        TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                bad.saveWithoutId(output);
                CompoundTag tag = output.buildResult();
                tag.putInt("cargo_version", 99);
                bad.cargo().load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), bad);
                long before = balance(server, player);
                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()),
                        "selling beside an unreadable pod");
                expectKey(
                        helper,
                        NOTHING_TO_SELL,
                        OreProcessor.sellCargo(context(server, player, processor)),
                        "selling again");
                String once = "Pod " + bad.getUUID() + ": cargo unreadable, not sold";
                long logged = log.errors().stream().filter(once::equals).count();
                if (balance(server, player) != before + OreType.SILVERIUM.value()
                        || good.cargoUsed() != 0
                        || bad.cargo().isReadable()) {
                    throw helper.assertionException(
                            "the readable pod is sold and the unreadable one is left: balance %s -> %s",
                            before, balance(server, player));
                }
                if (logged != 1) {
                    throw helper.assertionException(
                            "the skipped pod is logged once, \"%s\" appeared %s times in %s",
                            once, logged, log.errors());
                }
            } finally {
                good.discard();
                bad.discard();
            }
        });
        helper.succeed();
    }

    // ---- slag brick (#313) ----

    private static final String NO_SPOIL = "deepcharter.market.refusal.no_spoil";
    private static final String NO_ROOM = "deepcharter.market.refusal.no_room";

    private static void withSpoil(PodEntity pod, int spoil, int bricks) {
        PodLining.modify(pod, state -> new PodLining.State(spoil, bricks, 0, false, false));
    }

    private static int bricksCarried(ServerPlayer player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(SlagBrick.item())) {
                count += player.getInventory().getItem(slot).getCount();
            }
        }
        return count;
    }

    private static void fund(MinecraftServer server, ServerPlayer player, long dollars) {
        Charters.deposit(
                server,
                Charters.charterOfOrThrow(server, player.getUUID())
                        .orElseThrow()
                        .id(),
                dollars);
    }

    @GameTest
    public void fusingTurnsTwoSpoilIntoABrickForNothingAndKeepsTheRemainder(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Smelter", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity pod = podAt(helper, beside(processor, 4));
            withSpoil(pod, 7, 0);
            try {
                fund(server, player, 1000);
                long before = balance(server, player);
                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.FUSE_SPOIL, new CompoundTag()),
                        "fusing 7 spoil");
                PodLining.State after = PodLining.of(pod);
                if (after.bricks() != 3
                        || after.spoil() != 1
                        || balance(server, player) != before
                        || bricksCarried(player) != 0) {
                    throw helper.assertionException(
                            "7 spoil make 3 bricks for $0 and leave 1 spoil: rack %s, spoil %s, balance %s -> %s, carried %s",
                            after.bricks(), after.spoil(), before, balance(server, player), bricksCarried(player));
                }
                expectKey(
                        helper,
                        NO_SPOIL,
                        OreProcessor.fuseSpoil(context(server, player, processor)),
                        "fusing the single spoil left");
            } finally {
                pod.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void fusingFillsTheRackThenThePackAndNeverLosesSpoil(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Overflow", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity pod = podAt(helper, beside(processor, 4));
            // The rack holds 24: 22 in it leaves room for 2 of the 10 bricks, and the other 8 go to the pack.
            withSpoil(pod, 20, 22);
            try {
                fund(server, player, 1000);
                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.FUSE_SPOIL, new CompoundTag()),
                        "fusing into a nearly full rack");
                PodLining.State after = PodLining.of(pod);
                if (after.bricks() != 24 || after.spoil() != 0 || bricksCarried(player) != 8) {
                    throw helper.assertionException(
                            "2 bricks fill the rack and 8 go to the pack: rack %s, spoil %s, carried %s",
                            after.bricks(), after.spoil(), bricksCarried(player));
                }
                // A full rack and a pack with no room: nothing is made, nothing is charged.
                withSpoil(pod, 10, 24);
                for (int slot = 0;
                        slot < player.getInventory().getNonEquipmentItems().size();
                        slot++) {
                    player.getInventory().setItem(slot, new ItemStack(SlagBrick.item(), 64));
                }
                long before = balance(server, player);
                expectKey(
                        helper,
                        NO_ROOM,
                        OreProcessor.fuseSpoil(context(server, player, processor)),
                        "fusing with nowhere to put the brick");
                if (PodLining.of(pod).spoil() != 10 || balance(server, player) != before) {
                    throw helper.assertionException(
                            "a refused fuse takes no spoil and no money: spoil %s, balance %s -> %s",
                            PodLining.of(pod).spoil(), before, balance(server, player));
                }
            } finally {
                pod.discard();
            }
        });
        helper.succeed();
    }

    /** A full bay is 64 spoil, 32 bricks: the rack of 24 takes 24 and the pack the other 8, so a hopper dive refills both stores (#442). */
    @GameTest
    public void aFullBayFusesItsFirstTwentyFourBricksIntoAnEmptyRackAndTheRestIntoThePack(GameTestHelper helper) {
        expectFuse(
                helper,
                "Full bay",
                64,
                0,
                24,
                8,
                "fusing a full bay into an empty rack",
                "a full bay makes 32 bricks, 24 for the rack and 8 for the pack: rack %s, spoil %s, carried %s");
    }

    /** A save from before #442 can hold more bricks than the rack now takes: fusing leaves the rack as it is, sends every brick to the pack and loses none. */
    @GameTest
    public void aRackOverTheCapacityKeepsItsBricksAndFusesEverythingIntoThePack(GameTestHelper helper) {
        expectFuse(
                helper,
                "Old save",
                20,
                30,
                30,
                10,
                "fusing into a rack over its capacity",
                "the rack of 30 keeps its 30 and the 10 new bricks go to the pack: rack %s, spoil %s, carried %s");
    }

    /** Fuses all of a pod's spoil and expects the rack and the pack to hold the given bricks and no spoil to remain. */
    private static void expectFuse(
            GameTestHelper helper,
            String player,
            int spoil,
            int rack,
            int expectedRack,
            int expectedCarried,
            String what,
            String failure) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, player, true);
            ServerPlayer serverPlayer = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity pod = podAt(helper, beside(processor, 4));
            withSpoil(pod, spoil, rack);
            try {
                expectDone(
                        helper,
                        Terminals.act(serverPlayer, processor, OreProcessor.FUSE_SPOIL, new CompoundTag()),
                        what);
                PodLining.State after = PodLining.of(pod);
                if (after.bricks() != expectedRack
                        || after.spoil() != 0
                        || bricksCarried(serverPlayer) != expectedCarried) {
                    throw helper.assertionException(
                            failure, after.bricks(), after.spoil(), bricksCarried(serverPlayer));
                }
            } finally {
                pod.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void fusingNeedsSpoilAndNoMoney(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Pauper", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity pod = podAt(helper, beside(processor, 4));
            try {
                expectKey(
                        helper,
                        NO_SPOIL,
                        OreProcessor.fuseSpoil(context(server, player, processor)),
                        "fusing a pod with no spoil");
                withSpoil(pod, 1, 0);
                expectKey(
                        helper,
                        NO_SPOIL,
                        OreProcessor.fuseSpoil(context(server, player, processor)),
                        "fusing one spoil, which is under the 2 spoil a brick takes");
                // An empty account fuses all the same (#363): the brick costs spoil, not money.
                fund(server, player, 1000);
                Charters.spend(
                        server,
                        Charters.charterOfOrThrow(server, player.getUUID())
                                .orElseThrow()
                                .id(),
                        balance(server, player));
                withSpoil(pod, 4, 0);
                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.FUSE_SPOIL, new CompoundTag()),
                        "fusing with $0");
                if (PodLining.of(pod).bricks() != 2 || PodLining.of(pod).spoil() != 0 || balance(server, player) != 0) {
                    throw helper.assertionException(
                            "4 spoil make 2 bricks with no money: rack %s, spoil %s, balance %s",
                            PodLining.of(pod).bricks(), PodLining.of(pod).spoil(), balance(server, player));
                }
            } finally {
                pod.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void fusingTouchesOnlyThePodsTheCharterMayAccess(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Honest", true);
            MockPlayer rival = player(helper, "Dishonest", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity theirs = podAt(helper, beside(processor, 4));
            register(server, theirs, rival.player());
            withSpoil(theirs, 10, 0);
            try {
                fund(server, player, 1000);
                expectKey(
                        helper,
                        NO_POD,
                        OreProcessor.fuseSpoil(context(server, player, processor)),
                        "fusing the spoil of another charter's pod");
                PodEntity mine = podAt(helper, beside(processor, -4));
                register(server, mine, player);
                withSpoil(mine, 4, 0);
                try {
                    expectDone(
                            helper,
                            Terminals.act(player, processor, OreProcessor.FUSE_SPOIL, new CompoundTag()),
                            "fusing beside a rival's pod");
                    if (PodLining.of(mine).bricks() != 2
                            || PodLining.of(theirs).spoil() != 10
                            || PodLining.of(theirs).bricks() != 0) {
                        throw helper.assertionException(
                                "only the own pod is fused: own rack %s, rival spoil %s and rack %s",
                                PodLining.of(mine).bricks(),
                                PodLining.of(theirs).spoil(),
                                PodLining.of(theirs).bricks());
                    }
                } finally {
                    mine.discard();
                }
            } finally {
                theirs.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void aPodWithUnreadableLiningIsLeftAloneWhenFusing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Careful", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity bad = podAt(helper, beside(processor, 4));
            UnreadableChecks.makeUnreadable(bad, PodLining.STATE);
            try {
                fund(server, player, 1000);
                expectKey(
                        helper,
                        NO_SPOIL,
                        OreProcessor.fuseSpoil(context(server, player, processor)),
                        "fusing a pod whose lining cannot be read");
                if (!(bad.getAttached(PodLining.STATE) instanceof Versioned.Unreadable<PodLining.State>)) {
                    throw helper.assertionException("the unreadable lining state must be left as it was read");
                }
            } finally {
                bad.discard();
            }
        });
        helper.succeed();
    }

    // ---- salvage crates (#470) ----

    private static final String NO_SPOIL_FOR_CRATE = "deepcharter.market.refusal.no_spoil_for_crate";
    private static final String NO_ROOM_FOR_CRATE = "deepcharter.market.refusal.no_room_for_crate";

    private static int cratesCarried(ServerPlayer player) {
        return player.getInventory().countItem(SurfaceBlocks.SALVAGE_CRATE.asItem());
    }

    /** 55 spoil is two crates at 24 a crate (#470): 48 taken, 7 kept, no money charged and the brick rack and its dry flag left alone. */
    @GameTest
    public void pressingTurnsTwentyFourSpoilIntoACrateForNothingAndKeepsTheRemainder(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Presser", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity pod = podAt(helper, beside(processor, 4));
            PodLining.modify(pod, state -> new PodLining.State(55, 5, 3, false, true));
            try {
                fund(server, player, 1000);
                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.PRESS_CRATES, new CompoundTag()),
                        "pressing 55 spoil");
                PodLining.State after = PodLining.of(pod);
                if (cratesCarried(player) != 2
                        || after.spoil() != 7
                        || balance(server, player) != 1000
                        || after.bricks() != 5
                        || after.used() != 3
                        || !after.dry()) {
                    throw helper.assertionException(
                            "55 spoil press 2 crates for $0 and leave 7 spoil, the rack as it was: crates %s, state %s, balance %s",
                            cratesCarried(player), after, balance(server, player));
                }
            } finally {
                pod.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void pressingNeedsAFullCratesWorthOfSpoilAndNoMoney(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Pauper", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity pod = podAt(helper, beside(processor, 4));
            try {
                expectKey(
                        helper,
                        NO_SPOIL_FOR_CRATE,
                        OreProcessor.pressCrates(context(server, player, processor)),
                        "pressing a pod with no spoil");
                withSpoil(pod, 23, 0);
                expectKey(
                        helper,
                        NO_SPOIL_FOR_CRATE,
                        OreProcessor.pressCrates(context(server, player, processor)),
                        "pressing 23 spoil, one under a crate");
                if (PodLining.of(pod).spoil() != 23 || cratesCarried(player) != 0) {
                    throw helper.assertionException(
                            "a refused press takes nothing and makes nothing: spoil %s, crates %s",
                            PodLining.of(pod).spoil(), cratesCarried(player));
                }
                // An empty account presses all the same: the crate costs spoil, not money.
                withSpoil(pod, 24, 0);
                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.PRESS_CRATES, new CompoundTag()),
                        "pressing 24 spoil with $0");
                if (cratesCarried(player) != 1 || PodLining.of(pod).spoil() != 0 || balance(server, player) != 0) {
                    throw helper.assertionException(
                            "24 spoil press 1 crate with no money: crates %s, spoil %s, balance %s",
                            cratesCarried(player), PodLining.of(pod).spoil(), balance(server, player));
                }
            } finally {
                pod.discard();
            }
        });
        helper.succeed();
    }

    /** The crates go to the pack and nowhere else: a pack with room for one makes one and keeps the spoil of the other, and a full pack makes none. */
    @GameTest
    public void pressingStopsAtTheRoomInThePackAndKeepsTheRestOfTheSpoil(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Packed", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity pod = podAt(helper, beside(processor, 4));
            withSpoil(pod, 64, 0);
            try {
                int slots = player.getInventory().getNonEquipmentItems().size();
                for (int slot = 0; slot < slots; slot++) {
                    player.getInventory().setItem(slot, new ItemStack(SlagBrick.item(), 64));
                }
                player.getInventory().setItem(0, new ItemStack(SurfaceBlocks.SALVAGE_CRATE.asItem(), 63));
                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.PRESS_CRATES, new CompoundTag()),
                        "pressing with room for one crate");
                if (cratesCarried(player) != 64 || PodLining.of(pod).spoil() != 40) {
                    throw helper.assertionException(
                            "room for one crate makes one and keeps 40 spoil: crates %s, spoil %s",
                            cratesCarried(player), PodLining.of(pod).spoil());
                }
                expectKey(
                        helper,
                        NO_ROOM_FOR_CRATE,
                        OreProcessor.pressCrates(context(server, player, processor)),
                        "pressing into a full pack");
                if (PodLining.of(pod).spoil() != 40) {
                    throw helper.assertionException(
                            "a refused press takes no spoil, the pod holds %s",
                            PodLining.of(pod).spoil());
                }
            } finally {
                pod.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void pressingTakesFromEveryPodOfTheCharterAndOnlyThose(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Fleet", true);
            MockPlayer rival = player(helper, "Rival", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity theirs = podAt(helper, beside(processor, 4));
            register(server, theirs, rival.player());
            withSpoil(theirs, 64, 0);
            PodEntity first = podAt(helper, beside(processor, -4));
            PodEntity second = podAt(helper, beside(processor, -5));
            register(server, first, player);
            register(server, second, player);
            withSpoil(first, 40, 0);
            withSpoil(second, 40, 0);
            try {
                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.PRESS_CRATES, new CompoundTag()),
                        "pressing the spoil of two pods");
                // 40 and 40 spoil: one crate from each (16 left over in each), and the rival's pod is not touched.
                if (cratesCarried(player) != 2
                        || PodLining.of(first).spoil() != 16
                        || PodLining.of(second).spoil() != 16
                        || PodLining.of(theirs).spoil() != 64) {
                    throw helper.assertionException(
                            "one crate from each own pod and none from the rival's: crates %s, spoil %s and %s, rival %s",
                            cratesCarried(player),
                            PodLining.of(first).spoil(),
                            PodLining.of(second).spoil(),
                            PodLining.of(theirs).spoil());
                }
            } finally {
                first.discard();
                second.discard();
                theirs.discard();
            }
        });
        helper.succeed();
    }

    @GameTest
    public void aPodWithUnreadableLiningIsLeftAloneWhenPressing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Careful2", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity bad = podAt(helper, beside(processor, 4));
            UnreadableChecks.makeUnreadable(bad, PodLining.STATE);
            try {
                expectKey(
                        helper,
                        NO_SPOIL_FOR_CRATE,
                        OreProcessor.pressCrates(context(server, player, processor)),
                        "pressing a pod whose lining cannot be read");
                if (!(bad.getAttached(PodLining.STATE) instanceof Versioned.Unreadable<PodLining.State>)) {
                    throw helper.assertionException("the unreadable lining state must be left as it was read");
                }
            } finally {
                bad.discard();
            }
        });
        helper.succeed();
    }
    // ---- withdrawing ore (#536) ----

    private static final String BAD_WITHDRAWAL = "deepcharter.market.refusal.bad_withdrawal";
    private static final String NO_ORE_IN_CARGO = "deepcharter.market.refusal.no_ore_in_cargo";
    private static final String NO_ROOM_FOR_ORE = "deepcharter.market.refusal.no_room_for_ore";

    private static TerminalAction.Context withdrawal(
            MinecraftServer server, ServerPlayer player, BlockPos pos, String ore, int count) {
        return new TerminalAction.Context(
                server,
                player,
                Charters.charterOfOrThrow(server, player.getUUID()),
                TerminalTypes.ORE_PROCESSOR,
                pos,
                withdrawalArgs(ore, count));
    }

    private static CompoundTag withdrawalArgs(String ore, int count) {
        CompoundTag args = new CompoundTag();
        args.putString(OreProcessor.ORE_KEY, ore);
        args.putInt(OreProcessor.COUNT_KEY, count);
        return args;
    }

    private static int carried(ServerPlayer player, OreType ore) {
        return player.getInventory().countItem(OreRegistry.item(ore));
    }

    /**
     * Withdrawing moves ore from the cargo of the pods parked here into the pack and nothing else: the account is not paid, the other ores stay, and a
     * request for more than the cargo holds takes what it holds.
     */
    @GameTest
    public void withdrawingMovesCargoOreToThePackAndPaysNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Withdrawer", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity pod = podAt(
                    helper,
                    beside(processor, 4),
                    OreType.BRONZIUM,
                    OreType.BRONZIUM,
                    OreType.BRONZIUM,
                    OreType.IRONIUM);
            PodEntity second = podAt(helper, beside(processor, -4), OreType.BRONZIUM);
            try {
                fund(server, player, 500);
                long before = balance(server, player);
                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.WITHDRAW_ORE, withdrawalArgs("bronzium", 2)),
                        "withdrawing 2 Bronzium");
                if (carried(player, OreType.BRONZIUM) != 2
                        || pod.cargo().count(OreType.BRONZIUM) + second.cargo().count(OreType.BRONZIUM) != 2
                        || pod.cargo().count(OreType.IRONIUM) != 1
                        || balance(server, player) != before) {
                    throw helper.assertionException(
                            "2 Bronzium move to the pack and pay nothing: packed %s, cargo %s and %s, iron %s, balance %s -> %s",
                            carried(player, OreType.BRONZIUM),
                            pod.cargo().count(OreType.BRONZIUM),
                            second.cargo().count(OreType.BRONZIUM),
                            pod.cargo().count(OreType.IRONIUM),
                            before,
                            balance(server, player));
                }
                if (pod.cargoUsed() != pod.cargo().entries().size()) {
                    throw helper.assertionException(
                            "the pod's synced cargo count must follow its cargo: %s of %s",
                            pod.cargoUsed(), pod.cargo().entries().size());
                }
                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.WITHDRAW_ORE, withdrawalArgs("bronzium", 50)),
                        "withdrawing more Bronzium than the pods hold");
                if (carried(player, OreType.BRONZIUM) != 4
                        || pod.cargo().count(OreType.BRONZIUM) != 0
                        || second.cargo().count(OreType.BRONZIUM) != 0
                        || pod.cargo().count(OreType.IRONIUM) != 1
                        || balance(server, player) != before) {
                    throw helper.assertionException(
                            "asking for more takes what the pods hold, from both: packed %s, cargo %s and %s",
                            carried(player, OreType.BRONZIUM),
                            pod.cargo().count(OreType.BRONZIUM),
                            second.cargo().count(OreType.BRONZIUM));
                }
                expectKey(
                        helper,
                        NO_ORE_IN_CARGO,
                        OreProcessor.withdrawOre(withdrawal(server, player, processor, "bronzium", 1)),
                        "withdrawing an ore the pods no longer hold");
            } finally {
                pod.discard();
                second.discard();
            }
        });
        helper.succeed();
    }

    /** A request the client could not make honestly, an empty hold, no pod and a pod of another charter are refused, and each leaves the cargo and the pack as they were. */
    @GameTest
    public void withdrawingRefusesBadRequestsAndAnotherChartersPod(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Asker", true);
            MockPlayer rival = player(helper, "Neighbour", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            expectKey(
                    helper,
                    NO_POD,
                    OreProcessor.withdrawOre(withdrawal(server, player, processor, "bronzium", 1)),
                    "withdrawing with no pod parked");
            PodEntity theirs = podAt(helper, beside(processor, 4), OreType.BRONZIUM);
            register(server, theirs, rival.player());
            PodEntity mine = podAt(helper, beside(processor, -4), OreType.IRONIUM);
            register(server, mine, player);
            try {
                expectKey(
                        helper,
                        NO_ORE_IN_CARGO,
                        OreProcessor.withdrawOre(withdrawal(server, player, processor, "bronzium", 1)),
                        "withdrawing the Bronzium of another charter's pod");
                expectKey(
                        helper,
                        NO_ORE_IN_CARGO,
                        OreProcessor.withdrawOre(withdrawal(server, player, processor, "goldium", 1)),
                        "withdrawing an ore no pod holds");
                expectKey(
                        helper,
                        BAD_WITHDRAWAL,
                        OreProcessor.withdrawOre(withdrawal(server, player, processor, "stone", 1)),
                        "withdrawing a thing that is no ore");
                expectKey(
                        helper,
                        BAD_WITHDRAWAL,
                        OreProcessor.withdrawOre(withdrawal(server, player, processor, "ironium", 0)),
                        "withdrawing none");
                expectKey(
                        helper,
                        BAD_WITHDRAWAL,
                        OreProcessor.withdrawOre(withdrawal(server, player, processor, "ironium", -3)),
                        "withdrawing a negative count");
                expectKey(
                        helper,
                        BAD_WITHDRAWAL,
                        OreProcessor.withdrawOre(new TerminalAction.Context(
                                server,
                                player,
                                Charters.charterOfOrThrow(server, player.getUUID()),
                                TerminalTypes.ORE_PROCESSOR,
                                processor,
                                new CompoundTag())),
                        "withdrawing with no arguments");
                if (carried(player, OreType.IRONIUM) + carried(player, OreType.BRONZIUM) != 0
                        || theirs.cargo().count(OreType.BRONZIUM) != 1
                        || mine.cargo().count(OreType.IRONIUM) != 1) {
                    throw helper.assertionException("a refused withdrawal changes nothing");
                }
            } finally {
                theirs.discard();
                mine.discard();
            }
        });
        helper.succeed();
    }

    /** A player on no charter is refused by the terminal, and a pack with room for one takes one and a full pack none; the rest stays in the cargo. */
    @GameTest
    public void withdrawingStopsAtTheRoomInThePackAndNeedsACharter(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withProcessorOnline(server, () -> {
            MockPlayer drifter = player(helper, "Wanderer", false);
            BlockPos processor = processorFor(helper, drifter);
            PodEntity unowned = podAt(helper, beside(processor, 4), OreType.BRONZIUM);
            try {
                expectRefused(
                        helper,
                        TerminalRefusal.NOT_ON_A_CHARTER,
                        Terminals.act(
                                drifter.player(), processor, OreProcessor.WITHDRAW_ORE, withdrawalArgs("bronzium", 1)),
                        "a withdrawal by a player on no charter");
                if (unowned.cargo().count(OreType.BRONZIUM) != 1) {
                    throw helper.assertionException("a refused withdrawal leaves the cargo");
                }
            } finally {
                unowned.discard();
            }
            MockPlayer mock = player(helper, "Packed Up", true);
            ServerPlayer player = mock.player();
            BlockPos near = processorFor(helper, mock);
            PodEntity pod = podAt(helper, beside(near, 4), OreType.IRONIUM, OreType.IRONIUM, OreType.IRONIUM);
            try {
                int slots = player.getInventory().getNonEquipmentItems().size();
                for (int slot = 0; slot < slots; slot++) {
                    player.getInventory().setItem(slot, new ItemStack(SlagBrick.item(), 64));
                }
                player.getInventory()
                        .setItem(
                                0,
                                new ItemStack(
                                        OreRegistry.item(OreType.IRONIUM),
                                        OreRegistry.item(OreType.IRONIUM).getDefaultMaxStackSize() - 1));
                int packed = carried(player, OreType.IRONIUM);
                expectDone(
                        helper,
                        Terminals.act(player, near, OreProcessor.WITHDRAW_ORE, withdrawalArgs("ironium", 3)),
                        "withdrawing with room for one");
                if (carried(player, OreType.IRONIUM) != packed + 1
                        || pod.cargo().count(OreType.IRONIUM) != 2) {
                    throw helper.assertionException(
                            "room for one takes one and leaves 2 in the cargo: packed %s of %s, cargo %s",
                            carried(player, OreType.IRONIUM),
                            packed + 1,
                            pod.cargo().count(OreType.IRONIUM));
                }
                expectKey(
                        helper,
                        NO_ROOM_FOR_ORE,
                        OreProcessor.withdrawOre(withdrawal(server, player, near, "ironium", 1)),
                        "withdrawing into a full pack");
                if (pod.cargo().count(OreType.IRONIUM) != 2) {
                    throw helper.assertionException(
                            "a refused withdrawal takes nothing, the cargo holds %s",
                            pod.cargo().count(OreType.IRONIUM));
                }
            } finally {
                pod.discard();
            }
        });
        helper.succeed();
    }

    /** Cargo the build cannot read is refused and kept, and the processor's view skips it instead of throwing; the view counts the ores of the readable pods by ore. */
    @GameTest
    public void withdrawingLeavesUnreadableCargoAloneAndTheViewListsTheRest(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        withProcessorOnline(server, () -> {
            MockPlayer mock = player(helper, "Lister", true);
            ServerPlayer player = mock.player();
            BlockPos processor = processorFor(helper, mock);
            PodEntity good =
                    podAt(helper, beside(processor, -4), OreType.SILVERIUM, OreType.BRONZIUM, OreType.BRONZIUM);
            PodEntity bad = podAt(helper, beside(processor, 4), OreType.BRONZIUM);
            try {
                var charter = Charters.charterOfOrThrow(server, player.getUUID());
                ProcessorView view = OreProcessor.view(server, player, charter, processor);
                if (!view.cargo()
                        .equals(List.of(
                                new ProcessorView.Hold(OreType.BRONZIUM, 3),
                                new ProcessorView.Hold(OreType.SILVERIUM, 1)))) {
                    throw helper.assertionException("the view lists the ore of the pods, by ore: %s", view.cargo());
                }
                TagValueOutput output =
                        TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                bad.saveWithoutId(output);
                CompoundTag tag = output.buildResult();
                tag.putInt("cargo_version", 99);
                bad.cargo().load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), bad);
                ProcessorView skipped = OreProcessor.view(server, player, charter, processor);
                if (!skipped.cargo()
                        .equals(List.of(
                                new ProcessorView.Hold(OreType.BRONZIUM, 2),
                                new ProcessorView.Hold(OreType.SILVERIUM, 1)))) {
                    throw helper.assertionException(
                            "the view skips a pod whose cargo it cannot read and lists the other: %s", skipped.cargo());
                }
                expectDone(
                        helper,
                        Terminals.act(player, processor, OreProcessor.WITHDRAW_ORE, withdrawalArgs("bronzium", 5)),
                        "withdrawing beside a pod with unreadable cargo");
                if (carried(player, OreType.BRONZIUM) != 2 || good.cargo().count(OreType.BRONZIUM) != 0) {
                    throw helper.assertionException(
                            "the readable pod gives its 2 Bronzium: packed %s", carried(player, OreType.BRONZIUM));
                }
                good.discard();
                expectKey(
                        helper,
                        UNREADABLE_CARGO,
                        OreProcessor.withdrawOre(withdrawal(server, player, processor, "bronzium", 1)),
                        "withdrawing when only unreadable cargo is parked");
                if (bad.cargo().isReadable()) {
                    throw helper.assertionException("the unreadable cargo must be left as it was read");
                }
            } finally {
                good.discard();
                bad.discard();
            }
        });
        helper.succeed();
    }
}
