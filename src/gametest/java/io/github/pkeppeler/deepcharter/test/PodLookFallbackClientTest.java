package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderer;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PodLoads;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * Client GameTest for #243: a resource pack whose pod look file is broken must not crash the client on its reload. The renderer logs
 * one error that names the pack, the file and the place, and draws the mod's own look for that chassis. Two broken looks are tried: one
 * that is not JSON, and one that parses but names a cutter its model does not hold. The pack goes away again and the look returns.
 */
public class PodLookFallbackClientTest implements FabricClientGameTest {
    private static final String PACK = "pod-look-broken";
    private static final String PACK_ID = "file/" + PACK;
    private static final Identifier LOOK = Identifier.fromNamespaceAndPath("deepcharter", "pod/mole.json");

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        Path pack = context.computeOnClient(
                client -> client.getResourcePackDirectory().resolve(PACK));
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            int[] id = {0};
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity mole = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
                mole.setPos(player.position().add(0, 0, 4));
                player.level().addFreshEntity(mole);
                id[0] = mole.getId();
            });
            ClientWait.until(
                    context,
                    "the Mole on the client",
                    client -> client.level != null && client.level.getEntity(id[0]) instanceof PodEntity);
            String builtIn = context.computeOnClient(client -> loadedLook(client, id[0]));
            require(!builtIn.contains(PACK_ID), "the shipped look should not come from the test pack: " + builtIn);
            Identifier builtInTexture = context.computeOnClient(client -> drawnTexture(client, id[0]));
            try {
                for (String broken : new String[] {
                    "{ this is not json",
                    // The textures are swapped, so a renderer that kept this look would draw the wreck texture on an
                    // intact pod.
                    "{\"model\": \"deepcharter:pod/mole\", \"texture\": \"deepcharter:textures/entity/pod/mole_wreck.png\", \"cutters\": {\"0\": \"flute\"},"
                            + " \"wreck\": {\"texture\": \"deepcharter:textures/entity/pod/mole.png\"}}"
                }) {
                    writePack(pack, broken);
                    context.runOnClient(client -> {
                        PackRepository repository = client.getResourcePackRepository();
                        repository.reload();
                        if (!repository.addPack(PACK_ID)) {
                            throw new AssertionError("the pack " + PACK_ID + " could not be selected; available: "
                                    + repository.getAvailableIds());
                        }
                        client.options.updateResourcePacks(repository);
                    });
                    ClientWait.until(
                            context,
                            "the broken look reloaded",
                            client -> packLookIsActive(client) && client.gui.overlay() == null);
                    // The client is alive and the Mole draws with the look of the mod, whose source is not the pack.
                    context.waitTick(); // tick-wait: one frame draws the pod with the fallback look
                    String drawn = context.computeOnClient(client -> loadedLook(client, id[0]));
                    require(
                            drawn.equals(builtIn),
                            "a broken pack look should fall back to the mod's own look " + builtIn + ", draws "
                                    + drawn);
                    Identifier texture = context.computeOnClient(client -> drawnTexture(client, id[0]));
                    require(
                            texture.equals(builtInTexture),
                            "the Mole's renderer should draw the mod's own texture " + builtInTexture
                                    + " after the fallback, draws " + texture);
                    context.runOnClient(this::removePack);
                    ClientWait.until(
                            context,
                            "the broken pack gone",
                            client -> !packLookIsActive(client) && client.gui.overlay() == null);
                }
            } finally {
                context.runOnClient(this::removePack);
                ClientWait.until(
                        context,
                        "the pack removed",
                        client -> !packLookIsActive(client) && client.gui.overlay() == null);
                deleteTree(pack);
            }
        }
    }

    private static boolean packLookIsActive(Minecraft client) {
        return client.getResourceManager()
                .getResource(LOOK)
                .map(resource -> resource.sourcePackId().equals(PACK_ID))
                .orElse(false);
    }

    private void removePack(Minecraft client) {
        PackRepository repository = client.getResourcePackRepository();
        repository.removePack(PACK_ID);
        client.options.updateResourcePacks(repository);
    }

    /** The source of the look the game loads for the Mole now, held to the look the Mole's renderer draws. */
    private static String loadedLook(Minecraft client, int id) {
        PodEntity mole = (PodEntity) client.level.getEntity(id);
        return PodLoads.of(client, renderer(client, id), mole).look().source();
    }

    /** The texture the Mole's renderer draws it with now, which only the look the renderer holds decides. */
    private static Identifier drawnTexture(Minecraft client, int id) {
        return renderer(client, id)
                .appearanceOf((PodEntity) client.level.getEntity(id))
                .variant()
                .texture();
    }

    private static PodGeoRenderer renderer(Minecraft client, int id) {
        Object drawn = client.getEntityRenderDispatcher().getRenderer(client.level.getEntity(id));
        require(drawn instanceof PodGeoRenderer, "the Mole is not drawn with the GeckoLib renderer, but with " + drawn);
        return (PodGeoRenderer) drawn;
    }

    private static void writePack(Path pack, String look) {
        try {
            deleteTree(pack);
            Files.createDirectories(pack.resolve("assets/deepcharter/pod"));
            Files.writeString(
                    pack.resolve("pack.mcmeta"),
                    "{\"pack\": {\"description\": \"Deep Charter broken look (throwaway)\", \"min_format\": 97, \"max_format\": 97}}\n");
            Files.writeString(pack.resolve("assets/deepcharter/pod/mole.json"), look);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
