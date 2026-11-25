package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * The design tour's stills that have a subject, and the check that the subject is in sight. The colony's views are
 * measured from the anchors of the buildings they show, so {@code ColonyPlacementTest} checks them on the server
 * GameTest's own colony.
 */
public final class TourShots {
    /** How far a player's eyes are above the feet. */
    public static final double EYE = 1.62;
    /** The Host's plinth is this high (tools/colony/town.py PLINTH_TOP); his anchor is the block over it. */
    private static final int PLINTH_TOP = 5;
    /** The kit's blocks: the public block fields of {@link ColonyKit}. */
    private static final Set<Block> KIT = kitBlocks();

    /** The town as a whole, the subject of the views from the air and the orbit. */
    public static final Subject TOWN = new Subject("the town", TourShots::isColony, 40);

    private TourShots() {}

    /** What a still is of: a block that must stand within {@code radius} blocks of the point the camera looks at. */
    public record Subject(String what, Predicate<BlockState> test, double radius) {}

    /** A still of the colony: its name, where the camera stands and looks, its settle ticks, and what it shows. */
    public record Shot(String name, Vec3 eye, Vec3 target, int settleTicks, Subject subject) {}

    /**
     * A point {@code dx} east and {@code dz} south of the middle of the square (the foot of its plinth), {@code h}
     * above the square's paving.
     */
    public static Vec3 p(ColonySite.Placed colony, double dx, double h, double dz) {
        // The statue's anchor is the block he stands in, over his plinth: the paving's block is the plinth's height
        // below it.
        return rel(colony, ColonyAnchor.STATUE, dx, h - PLINTH_TOP, dz);
    }

    /** A point {@code dx} east, {@code dy} up and {@code dz} south of the bottom centre of {@code anchor}'s block. */
    public static Vec3 rel(ColonySite.Placed colony, ColonyAnchor anchor, double dx, double dy, double dz) {
        return Vec3.atBottomCenterOf(TestColony.anchor(colony, anchor)).add(dx, dy, dz);
    }

    /** The point the views from the air and the orbit look at. */
    public static Vec3 middle(ColonySite.Placed colony) {
        return p(colony, 0, 6, -6);
    }

    /** The views of the town from the air and from its margin, which the tour shoots before its orbit. */
    public static List<Shot> overviews(ColonySite.Placed colony) {
        Vec3 middle = middle(colony);
        return List.of(
                new Shot("colony-aerial-south", p(colony, 0, 38, 62), middle, 80, TOWN),
                new Shot("colony-aerial-northwest", p(colony, -52, 38, -52), middle, 30, TOWN),
                new Shot("colony-aerial-northeast", p(colony, 52, 38, -52), middle, 30, TOWN),
                // From out on the graded land, looking back at the pad's edge: the south margin, and the east
                // side where the land is higher.
                new Shot("colony-margin-looking-back-south", p(colony, 14, 12, 60), p(colony, 0, 6, 8), 30, TOWN),
                new Shot("colony-margin-from-the-east", p(colony, 60, 14, 8), p(colony, 0, 6, -4), 30, TOWN),
                new Shot("colony-from-straight-above", p(colony, 0, 80, 6), p(colony, 0, 0, -2), 30, TOWN),
                new Shot(
                        "colony-from-the-south-edge",
                        p(colony, 0, EYE, 11.5),
                        p(colony, 0, 9, 0),
                        30,
                        new Subject("the plinth", state -> state.is(ColonyKit.BRASS_TRIM), 8)));
    }

    /**
     * The close-ups, which the tour shoots after its orbit, each from the open side of its building (the square for the
     * terminals, the street for the doors).
     */
    public static List<Shot> closeUps(ColonySite.Placed colony) {
        List<Shot> shots = new ArrayList<>();
        for (TerminalType type : TerminalTypes.all()) {
            Optional<ColonyAnchor> anchor = ColonyAnchor.forTerminal(type);
            if (anchor.isPresent()) {
                Vec3 terminal = rel(colony, anchor.get(), 0, 0, 0);
                shots.add(new Shot(
                        "terminal-" + type.id().getPath().replace('_', '-'),
                        terminal.add(0, 2.0, 4.5),
                        terminal.add(0, 0.5, 0),
                        20,
                        new Subject("the " + type.id().getPath() + " terminal", state -> state.is(type.block()), 1.5)));
            }
        }
        Vec3 upgrade = rel(colony, ColonyAnchor.UPGRADE_TERMINAL, 0, 0, 0);
        // From the south-east, past the square's corner, so that the Host's plinth, which stands in line with the
        // upgrade terminal, is not in the line of sight; and high enough to look over the top of the square's lamp
        // post 10 east and 4 south of the Host.
        shots.add(new Shot(
                "terminal-row",
                upgrade.add(12, 5, 14),
                upgrade.add(0, 0.5, 0),
                20,
                new Subject(
                        "a terminal",
                        state -> TerminalTypes.all().stream().anyMatch(type -> state.is(type.block())),
                        1.5)));
        Subject plinth = new Subject("the Host's plinth", state -> state.is(ColonyKit.BRASS_TRIM), 9);
        shots.add(new Shot("statue-from-the-square", p(colony, 6, 2.5, 12), p(colony, 0, 9, 0), 20, plinth));
        shots.add(new Shot("statue-close", p(colony, 6, 3, 9), p(colony, 0, 11, 0), 20, plinth));
        shots.add(new Shot("statue-hands-from-above", p(colony, -6, 22, 16), p(colony, 0, 13, 0), 20, plinth));

        Vec3 office = rel(colony, ColonyAnchor.CONTINUITY_OFFICE, 0, 0, 0);
        shots.add(new Shot(
                "continuity-office-from-the-square",
                office.add(-12, EYE, 0),
                office.add(0, 1.3, 0),
                40,
                new Subject("the lectern", state -> state.is(Blocks.LECTERN), 6)));
        shots.add(new Shot(
                "continuity-office-inside",
                office.add(-2, 2.5, 0),
                office.add(3, 1.5, -3),
                20,
                new Subject("the lectern", state -> state.is(Blocks.LECTERN), 2.5)));

        // The hangar's bay faces the west street: shot from the square through it, at the pad the pods stand on.
        Vec3 hangar = rel(colony, ColonyAnchor.HANGAR, 0, 0, 0);
        shots.add(new Shot(
                "hangar-from-the-square",
                hangar.add(19, EYE, 0),
                hangar.add(0, 2.3, 0),
                40,
                new Subject("the hangar's pad", state -> state.is(ColonyKit.RIVETED_PLATE), 3)));

        // The chapel's door is on the west street: shot through it, along the aisle to the altar.
        Vec3 candle = rel(colony, ColonyAnchor.CHAPEL_CANDLE, 0, 0, 0);
        Subject lit = new Subject(
                "the lit chapel candle", state -> state.is(Blocks.CANDLE) && state.getValue(CandleBlock.LIT), 1.5);
        shots.add(new Shot("chapel-from-the-square", candle.add(11, 0.62, 0), candle.add(0, 0.3, 0), 40, lit));
        shots.add(new Shot("chapel-altar-and-candle", candle.add(4.5, 0.62, 0), candle.add(-0.5, 0.3, 0), 20, lit));

        // The doors of the south row open on the south street, across which the square is in sight.
        Vec3 bunkhouse = rel(colony, ColonyAnchor.BUNKHOUSE, 0, 0, 0);
        shots.add(new Shot(
                "bunkhouse-from-the-square",
                bunkhouse.add(0.0, EYE, -9),
                bunkhouse.add(0, 1.3, 0),
                40,
                new Subject("a bed", state -> state.getBlock() instanceof BedBlock, 4)));
        Vec3 pay = rel(colony, ColonyAnchor.PAY_OFFICE, 0, 0, 0);
        shots.add(new Shot(
                "pay-office-from-the-square",
                pay.add(0, EYE, -7),
                pay.add(0, 1.3, 2.5),
                40,
                new Subject("the grille", state -> state.is(ColonyKit.GRATING), 3)));
        Vec3 personnel = rel(colony, ColonyAnchor.PERSONNEL_OFFICE, 0, 0, 0);
        shots.add(new Shot(
                "personnel-office-from-the-square",
                personnel.add(0, EYE, -8),
                personnel.add(0, 1.5, 2),
                40,
                new Subject("Joy's calendar (a Note)", state -> state.getBlock() instanceof NoteBlock, 3)));
        // The bar's door faces the square across the street, so it is shot from the square itself.
        Vec3 bar = rel(colony, ColonyAnchor.LAMP_AND_PICK, 0, 0, 0);
        shots.add(new Shot(
                "lamp-and-pick-from-the-square",
                bar.add(0, EYE, -9),
                bar.add(0, 0.3, 2),
                40,
                new Subject("the coal blocks of the bar", state -> state.is(Blocks.COAL_BLOCK), 3)));

        // The Conduit stands in the headframe: its casing shows through the open front of the tower, and the tower
        // is the subject from the sides.
        Vec3 conduit = rel(colony, ColonyAnchor.CONDUIT, 0, 0, 0);
        Subject casing = new Subject("the conduit casing", state -> state.is(ColonyBlocks.CONDUIT), 3);
        Subject tower = new Subject(
                "the headframe", state -> state.is(ColonyKit.LATTICE_GIRDER) || state.is(ColonyBlocks.CONDUIT), 9);
        shots.add(new Shot("conduit-from-the-square", conduit.add(0, 5, 16), conduit.add(0, 5, 0), 30, casing));
        shots.add(new Shot("conduit-from-the-west", conduit.add(-20, 12, 8), conduit.add(0, 9, 0), 20, tower));
        shots.add(new Shot("conduit-from-the-north", conduit.add(6, 26, -22), conduit.add(0, 10, 0), 20, tower));
        return shots;
    }

    /**
     * Why {@code subject} is not in sight from {@code eye} looking at {@code target}, or null if it is: no block of the
     * subject is within its radius of {@code target}, or something else stands in the line of sight and hides it.
     */
    public static @Nullable String problem(ServerLevel level, Vec3 eye, Vec3 target, Subject subject) {
        BlockPos centre = BlockPos.containing(target);
        int reach = (int) Math.ceil(subject.radius());
        boolean found = BlockPos.betweenClosedStream(
                        centre.offset(-reach, -reach, -reach), centre.offset(reach, reach, reach))
                .anyMatch(pos -> Vec3.atCenterOf(pos).distanceTo(target) <= subject.radius()
                        && subject.test().test(level.getBlockState(pos)));
        if (!found) {
            return "no " + subject.what() + " within " + subject.radius() + " blocks of " + target;
        }
        BlockHitResult hit = level.clip(new ClipContext(
                eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) {
            return null;
        }
        BlockState first = level.getBlockState(hit.getBlockPos());
        if (!subject.test().test(first) && hit.getLocation().distanceTo(target) > subject.radius() + 1) {
            return first + " at " + hit.getBlockPos().toShortString() + " is in the way of " + subject.what()
                    + " (from " + eye + " to " + target + ", hit at " + hit.getLocation() + ")";
        }
        return null;
    }

    /** A block of the colony's kit: what the colony is made of, so a view of the town or of a wall has a subject. */
    private static boolean isColony(BlockState state) {
        return KIT.contains(state.getBlock()) || state.is(ColonyBlocks.CONDUIT) || state.is(ColonyBlocks.COMPANY_LAMP);
    }

    private static Set<Block> kitBlocks() {
        Set<Block> blocks = new HashSet<>();
        for (Field field : ColonyKit.class.getFields()) {
            if (Block.class.isAssignableFrom(field.getType())) {
                try {
                    blocks.add((Block) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("cannot read the kit block " + field.getName(), e);
                }
            }
        }
        return blocks;
    }
}
