package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.CAMPAIGN;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.LAYER_1_SCENE_HEIGHT;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.SITE;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.TEST_PRESET;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.buildRoom;
import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.sound.AmbienceTicker;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceFragments;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceRegistry;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceTier;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceTuning;
import io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.Holds;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientLight.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Client GameTest for #866: in layer 2, beside a Company horn, the client's ambience ticker plays the horn's warm-up crackle and then a
 * fragment of the worn tier or of the horn's own pool (#946), both from the horn; and a hymn that is playing holds it back until the hymn has ended. The ticker's tuning is
 * swapped for gaps of a few ticks ({@code AmbienceTicker.restart}), since the real ones are minutes. It runs in the campaign world's test
 * dimension, where a layer is a Y band of one level.
 */
public class AmbienceClientTest implements FabricClientGameTest {
    private static final int HORN_DX = 2;
    private static final int HORN_DY = 2;

    /** Gaps of a few ticks, and the real warm-up, quiet window and prefixes. */
    private static final AmbienceTuning QUICK = new AmbienceTuning(
            List.of(new AmbienceTuning.Rate(AmbienceTier.WORN, 40, 60)),
            20,
            30,
            40,
            AmbienceTuning.DEFAULT.quietPrefixes(),
            AmbienceTuning.DEFAULT.hornRange(),
            40,
            60,
            20,
            AmbienceTuning.DEFAULT.hornWarmupTicks(),
            AmbienceTuning.DEFAULT.distanceMin(),
            AmbienceTuning.DEFAULT.distanceMax(),
            AmbienceTuning.DEFAULT.spotTries());

    /** Every sound the client starts, in order. */
    private static final class Heard implements SoundEventListener {
        private final List<SoundInstance> played = new CopyOnWriteArrayList<>();

        @Override
        public void onPlaySound(SoundInstance sound, WeighedSoundEvents events, float range) {
            if (played.stream().noneMatch(seen -> seen == sound)) {
                played.add(sound);
            }
        }

        List<SoundInstance> ambience() {
            return played.stream()
                    .filter(sound -> sound.getIdentifier().getPath().startsWith("ambience."))
                    .toList();
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        Heard heard = new Heard();
        context.runOnClient(client -> client.getSoundManager().addListener(heard));
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(state -> state.setWorldType(new WorldTypeEntry(state.getSettings()
                        .worldgenLoadContext()
                        .lookupOrThrow(Registries.WORLD_PRESET)
                        .getOrThrow(TEST_PRESET))))
                .create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            TestServerContext server = singleplayer.getServer();
            try (Holds holds = Holds.of(server)) {
                BlockPos floor = new BlockPos(SITE, CampaignBands.layer(2).minY() + LAYER_1_SCENE_HEIGHT, SITE);
                BlockPos horn = floor.offset(HORN_DX, HORN_DY, 0);
                buildRoom(server, holds, CAMPAIGN, floor, Optional.empty(), false);
                server.runOnServer(s -> {
                    s.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SPECTATOR);
                    ServerLevel level = ClientLight.serverLevel(s, CAMPAIGN);
                    level.setBlock(horn, AmbienceRegistry.COMPANY_HORN.defaultBlockState(), 3);
                    s.getPlayerList()
                            .getPlayers()
                            .getFirst()
                            .teleportTo(
                                    level, floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5, Set.of(), 0, 0, true);
                });
                Box around = Box.around(floor, 4, 1, 4);
                ClientWait.until(
                        context,
                        "the client in layer 2 holding the room and its horn",
                        client -> ClientLight.clientLevel(client).dimension().equals(CAMPAIGN)
                                && client.player != null
                                && client.player.blockPosition().equals(floor)
                                && ClientLight.chunksPresent(ClientLight.clientLevel(client), around)
                                && ClientLight.clientLevel(client)
                                        .getBlockState(horn)
                                        .is(AmbienceRegistry.COMPANY_HORN),
                        client -> "no room or horn at " + floor.toShortString());
                int layer = context.computeOnClient(
                        client -> LayerChain.bandAt(ClientLight.clientLevel(client), floor.getY())
                                .orElseThrow()
                                .layer());
                require(layer == 2, "the room is in layer " + layer + ", not 2");

                context.runOnClient(client -> AmbienceTicker.restart(QUICK));
                // tick-wait: the ticker builds its sound tracker on its first tick after the restart, and nothing
                // reports it
                context.waitTicks(5);
                SoundInstance[] hymn = new SoundInstance[1];
                context.runOnClient(client -> {
                    hymn[0] = SimpleSoundInstance.forUI(DeepSound.HYMN_LAYER_2.event(), 1f);
                    client.getSoundManager().play(hymn[0]);
                });
                ClientWait.until(
                        context,
                        "the hymn to play and end",
                        client -> client.getSoundManager().isActive(hymn[0])
                                || !heard.ambience().isEmpty(),
                        client -> "the hymn never played");
                ClientWait.until(
                        context,
                        "the hymn to end",
                        client -> !client.getSoundManager().isActive(hymn[0])
                                || !heard.ambience().isEmpty());
                require(
                        heard.ambience().isEmpty(),
                        "a fragment played while the hymn was playing: " + heard.ambience());

                ClientWait.until(
                        context,
                        "the horn's warm-up and a worn fragment or a horn sound, after the hymn",
                        client -> heard.ambience().size() >= 2,
                        client -> "heard "
                                + heard.ambience().stream()
                                        .map(SoundInstance::getIdentifier)
                                        .toList());
                List<SoundInstance> ambience = heard.ambience();
                require(
                        ambience.getFirst().getIdentifier().getPath().equals("ambience.horn_warmup"),
                        "the first was " + ambience.getFirst().getIdentifier() + ", not the horn's warm-up");
                SoundInstance fragment = ambience.get(1);
                require(
                        hornFragments().contains(fragment.getIdentifier()),
                        "the second was " + fragment.getIdentifier() + ", not a worn fragment or a horn sound");
                Vec3 at = Vec3.atCenterOf(horn);
                for (SoundInstance sound : ambience.subList(0, 2)) {
                    require(
                            Math.abs(sound.getX() - at.x) < 0.01
                                    && Math.abs(sound.getY() - at.y) < 0.01
                                    && Math.abs(sound.getZ() - at.z) < 0.01,
                            sound.getIdentifier() + " did not come from the horn at " + horn.toShortString());
                }
            }
        } finally {
            context.runOnClient(client -> {
                client.getSoundManager().removeListener(heard);
                AmbienceTicker.restart(AmbienceTuning.DEFAULT);
            });
        }
    }

    /** What a horn in layer 2 may follow its warm-up with: a fragment of the worn tier or one of the horn's own pool. */
    private static Set<Identifier> hornFragments() {
        Set<Identifier> sounds = new HashSet<>();
        for (String name : AmbienceFragments.pools().getOrDefault(AmbienceTier.WORN, List.of())) {
            sounds.add(AmbienceFragments.sound(AmbienceTier.WORN, name).location());
        }
        for (String name : AmbienceFragments.hornPool()) {
            sounds.add(AmbienceFragments.hornSound(name).location());
        }
        return sounds;
    }
}
