package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.BoreBot.ROTOR;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.JUMP;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.SPRINT;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import java.util.List;
import net.minecraft.world.entity.player.Input;

/**
 * A scripted pilot for a first trip, in the pilot's own inputs: sprint to drill down, push east into the wall to dig sideways, back
 * west out of the tunnel, hold the rotor to climb. One {@link #step} each server tick. It measures what the trip costs in the
 * player's units (blocks, seconds, litres), so a tuning change shows in numbers a player would feel.
 *
 * <p>The pod starts at a pad {@code dropBlocks} above {@code floorY}, the first stone, and falls to it with the rotor braking as a
 * pilot does (under the hard landing speed).
 */
public final class TripPilot {
    private static final Input BACK_WEST = new Input(false, true, false, false, false, false, false);
    private static final int TICKS_PER_SECOND = 20;
    /** How far a side tunnel goes, in blocks. */
    public static final int SIDE_BLOCKS = 6;

    /** Where the pilot is in the script. */
    public enum Phase {
        DESCEND,
        BORE,
        SIDE_OUT,
        SIDE_BACK,
        CLIMB,
        HOME,
        STRANDED,
        BED_END
    }

    private final PodEntity pod;
    private final MockPlayer pilot;
    private final int floorY;
    private final int padY;
    private final int bedBlocks;
    private final List<Integer> sideDigsAt;
    private final int turnaround;
    private final float tankLitres;

    private Phase phase = Phase.DESCEND;
    private int ticks;
    private int nextSideDig;
    private double sideStartX;
    private int landedTick = -1;
    private double landedLitres;
    private int climbTick = -1;
    private double climbLitres;
    private int lowBeepTick = -1;

    /**
     * @param floorY the first block of air above the stone bed: the pod lands on it
     * @param padY the height the pod starts at, the colony pad's stand-in
     * @param bedBlocks how deep the stone bed goes below {@code floorY}
     * @param sideDigsAt the depths, in blocks below {@code floorY}, at which the pilot digs a side tunnel and backs out of it; the
     *     last one is the turnaround, and the pilot climbs home after it. Empty: a straight bore down until the tank is dry.
     */
    public TripPilot(PodEntity pod, MockPlayer pilot, int floorY, int padY, int bedBlocks, List<Integer> sideDigsAt) {
        this.pod = pod;
        this.pilot = pilot;
        this.floorY = floorY;
        this.padY = padY;
        this.bedBlocks = bedBlocks;
        this.sideDigsAt = List.copyOf(sideDigsAt);
        this.turnaround = sideDigsAt.isEmpty() ? bedBlocks : sideDigsAt.getLast();
        this.tankLitres = PodStats.of(pod).tankLitres();
    }

    public Phase phase() {
        return phase;
    }

    public boolean finished() {
        return phase == Phase.HOME || phase == Phase.STRANDED || phase == Phase.BED_END;
    }

    /** Litres in the tank now. */
    public double litres() {
        return pod.fuel() / PodTuning.DEFAULT.shell().fullFuel() * tankLitres;
    }

    public double tankLitres() {
        return tankLitres;
    }

    /** Whole blocks bored straight down so far (the pod rests {@code n} below the floor after {@code n} slabs). */
    public int blocksDown() {
        return (int) Math.round(Math.max(0, floorY - pod.getY()));
    }

    /** Seconds since the script began, in pod ticks of the script. */
    public double seconds() {
        return ticks / (double) TICKS_PER_SECOND;
    }

    /** Seconds from the first touch of the stone to the end of the script. */
    public double secondsOnTheStone() {
        return landedTick < 0 ? 0 : (ticks - landedTick) / (double) TICKS_PER_SECOND;
    }

    /** Litres the fall to the stone cost (the pad to the first block). */
    public double descentLitres() {
        return landedTick < 0 ? 0 : tankLitres - landedLitres;
    }

    /** Litres the climb from the turnaround to the pad cost; 0 before the climb starts. */
    public double climbLitres() {
        return climbTick < 0 ? 0 : climbLitres - litres();
    }

    /** Seconds the climb took so far; 0 before it starts. */
    public double climbSeconds() {
        return climbTick < 0 ? 0 : (ticks - climbTick) / (double) TICKS_PER_SECOND;
    }

    /** Seconds into the script at which the fuel first fell to the low-fuel beep threshold, or -1. */
    public double lowBeepSeconds() {
        return lowBeepTick < 0 ? -1 : lowBeepTick / (double) TICKS_PER_SECOND;
    }

    /** Sets the pilot's inputs for the next tick. Call once a server tick while the pod is in a ticking chunk. */
    public void step() {
        if (finished()) {
            pilot.releaseInput();
            return;
        }
        ticks++;
        if (lowBeepTick < 0 && pod.fuel() <= PodTuning.DEFAULT.fuel().lowFuelPercent()) {
            lowBeepTick = ticks;
        }
        if (pod.stranded()) {
            phase = Phase.STRANDED;
            pilot.releaseInput();
            return;
        }
        pilot.setInput(inputFor());
    }

    private Input inputFor() {
        boolean braking = !pod.onGround() && -pod.getDeltaMovement().y > EarlyRunModel.DRIVE_DOWN_SINK;
        return switch (phase) {
            case DESCEND -> {
                if (pod.onGround() && pod.getY() <= floorY + 0.5) {
                    phase = Phase.BORE;
                    landedTick = ticks;
                    landedLitres = litres();
                    yield SPRINT;
                }
                yield braking ? ROTOR : Input.EMPTY;
            }
            case BORE -> {
                int target = nextSideDig < sideDigsAt.size() ? sideDigsAt.get(nextSideDig) : turnaround;
                if (blocksDown() >= bedBlocks && pod.onGround()) {
                    phase = Phase.BED_END;
                    yield Input.EMPTY;
                }
                if (!sideDigsAt.isEmpty() && blocksDown() >= target && pod.onGround()) {
                    phase = Phase.SIDE_OUT;
                    sideStartX = pod.getX();
                    yield FORWARD;
                }
                yield braking ? BoreBot.withRotor(SPRINT) : SPRINT;
            }
            case SIDE_OUT -> {
                if (pod.getX() >= sideStartX + SIDE_BLOCKS) {
                    phase = Phase.SIDE_BACK;
                    yield BACK_WEST;
                }
                yield FORWARD;
            }
            case SIDE_BACK -> {
                if (pod.getX() > sideStartX) {
                    yield BACK_WEST;
                }
                nextSideDig++;
                if (nextSideDig >= sideDigsAt.size()) {
                    phase = Phase.CLIMB;
                    climbTick = ticks;
                    climbLitres = litres();
                    yield JUMP;
                }
                phase = Phase.BORE;
                yield SPRINT;
            }
            case CLIMB -> {
                if (pod.getY() >= padY) {
                    phase = Phase.HOME;
                    yield Input.EMPTY;
                }
                yield JUMP;
            }
            case HOME, STRANDED, BED_END -> Input.EMPTY;
        };
    }
}
