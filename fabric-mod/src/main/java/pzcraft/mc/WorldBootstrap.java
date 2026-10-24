package pzcraft.mc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Opens (or creates) the hidden void world Steve lives in, straight from the title screen: one world for each PZ save. PZ
 * says which save it has loaded (MSG_PZ_WORLD, "<game mode>/<save>"); this opens that save's world, and when PZ has moved to
 * another save it first saves and leaves the open one ("Save and Quit to Title") and opens the other. With no save loaded
 * in PZ (its main menu) nothing is opened and an open world stays as it is. Which world belongs to which save is kept in
 * {@code saves/pzcraft-worlds.properties}; the one world all saves shared before ("pzcraft") goes to the first save that
 * asks. A PZ that never says (an older PZ jar, the test harness) gets that shared world after 15 s, as before. The worlds have
 * no terrain: everything Steve stands on comes from PZ's collision field or from blocks placed.
 */
final class WorldBootstrap {
    /** The world every PZ save shared before there was one per save. */
    static final String LEGACY_LEVEL_ID = "pzcraft";
    private static final String MAP_FILE = "pzcraft-worlds.properties";

    /** Ticks PZ has been connected without saying which save it has (an older PZ jar, or the test harness). */
    private static final int SILENT_FALLBACK_TICKS = 300;
    private static int idleTicks, silentTicks;
    private static boolean leaving;
    private static String cachedKey, cachedLevel;

    private WorldBootstrap() {}

    /** Client thread, every tick. */
    static void tick(Minecraft mc) {
        String server = Session.joinServer;
        if (server != null && !server.isEmpty()) { joinServer(mc, server); return; }   // multiplayer: the host's server, no world of our own
        String key = Session.pzWorld;
        String open = openLevelId(mc);
        String level = key == null || key.isEmpty() ? null : cachedLevelFor(mc, key);
        Session.mcWorld = open != null && open.equals(level) ? key : "";
        silentTicks = Session.pzConnected && key == null ? silentTicks + 1 : 0;
        if (key == null && silentTicks > SILENT_FALLBACK_TICKS) {    // as before there was one world per save
            key = "(no save reported)";
            level = LEGACY_LEVEL_ID;
        }
        //#if MC_26_1
        //$ if (level == null || mc.getOverlay() != null) return;
        //#else
        if (level == null || mc.gui.overlay() != null) return;
        //#endif
        if (mc.level != null) {
            if (open != null && !open.equals(level) && !leaving) {
                PzCraftMain.LOG.info("PZ is on save '{}': saving and leaving world '{}' for '{}'", key, open, level);
                leaving = true;
                Session.mcWorld = "";
                mc.disconnectFromWorld(Component.literal("Project-ZombCraft: changing world"));
            }
            return;
        }
        //#if MC_26_1
        //$ Screen screen = mc.screen;
        //#else
        Screen screen = mc.gui.screen();
        //#endif
        if (!(screen instanceof TitleScreen || screen instanceof AccessibilityOnboardingScreen)) return;
        if (++idleTicks < 20) return;
        idleTicks = 0;
        leaving = false;
        // what PZ streamed for the last world (its collision, materials, what was covered) is not this one's
        CollisionField.reset();
        MaterialField.reset();
        TerrainCoverage.reset();
        PzTargets.reset();
        PzCars.reset();
        PzFire.reset();
        open(mc, level, key);
    }

    private static long nextJoin;

    /**
     * Multiplayer: PZ's server named the host's Minecraft server; this client plays there, not in a world of its own. Leaves
     * whatever else is open (a world of its own, another server), then connects from the title screen, again every 10 s
     * after a failed attempt. Reports "server:<address>" as its world once in, which PZ compares with its own.
     */
    private static void joinServer(Minecraft mc, String address) {
        var current = mc.getCurrentServer();
        boolean inIt = mc.level != null && current != null && !mc.isLocalServer() && address.equals(current.ip);
        Session.mcWorld = inIt ? "server:" + address : "";
        //#if MC_26_1
        //$ if (inIt || mc.getOverlay() != null) return;
        //#else
        if (inIt || mc.gui.overlay() != null) return;
        //#endif
        if (mc.level != null) {
            if (!leaving) {
                PzCraftMain.LOG.info("PZ's server plays on Minecraft server {}: leaving {}", address, current == null ? "this world" : current.ip);
                leaving = true;
                mc.disconnectFromWorld(Component.literal("Project-ZombCraft: joining the multiplayer server"));
            }
            return;
        }
        //#if MC_26_1
        //$ Screen screen = mc.screen;
        //#else
        Screen screen = mc.gui.screen();
        //#endif
        boolean idle = screen instanceof TitleScreen || screen instanceof AccessibilityOnboardingScreen
                || screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen;
        long now = System.currentTimeMillis();
        if (!idle || now < nextJoin) return;
        nextJoin = now + 10_000;
        leaving = false;
        CollisionField.reset();
        MaterialField.reset();
        TerrainCoverage.reset();
        PzTargets.reset();
        PzCars.reset();
        PzFire.reset();
        PzCraftMain.LOG.info("joining the multiplayer Minecraft server {}", address);
        //#if MC_1_20
        //$ net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new TitleScreen(), mc,
        //$         net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),
        //$         new net.minecraft.client.multiplayer.ServerData("Project-ZombCraft multiplayer", address, false), false);
        //#else
        net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new TitleScreen(), mc,
                net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),
                new net.minecraft.client.multiplayer.ServerData("Project-ZombCraft multiplayer", address, net.minecraft.client.multiplayer.ServerData.Type.OTHER),
                false, null);
        //#endif
    }

    /** The folder name of the world open in the integrated server, or null. */
    private static String openLevelId(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (mc.level == null || server == null) return null;
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return root.getFileName() == null ? null : root.getFileName().toString();
    }

    private static void open(Minecraft mc, String level, String key) {
        if (mc.getLevelSource().levelExists(level)) {
            PzCraftMain.LOG.info("Opening hidden world '{}' for PZ save '{}'", level, key);
            //#if MC_1_20
            //$ mc.createWorldOpenFlows().loadLevel(new TitleScreen(), level);
            //#else
            mc.createWorldOpenFlows().openWorld(level, () -> mc.setScreenAndShow(new TitleScreen()));
            //#endif
        } else {
            PzCraftMain.LOG.info("Creating hidden world '{}' for PZ save '{}'", level, key);
            String name = "Project-ZombCraft " + key.substring(key.indexOf('/') + 1);
            //#if MC_1_20
            //$ // 1.20.1 has no difficulty lock in LevelSettings; Peaceful, commands on, as on 26.x
            //$ LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true,
            //$         new net.minecraft.world.level.GameRules(), WorldDataConfiguration.DEFAULT);
            //$ mc.createWorldOpenFlows().createFreshLevel(level, settings, new WorldOptions(0L, false, false), WorldBootstrap::voidDimensions);
            //#else
            LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, true), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(level, settings, new WorldOptions(0L, false, false),
                    WorldBootstrap::voidDimensions, new TitleScreen());
            //#endif
        }
    }

    private static String cachedLevelFor(Minecraft mc, String key) {
        if (key.equals(cachedKey)) return cachedLevel;
        try {
            cachedLevel = levelFor(mc.getLevelSource().getBaseDir().resolve(MAP_FILE), key, mc.getLevelSource().levelExists(LEGACY_LEVEL_ID));
            cachedKey = key;
            return cachedLevel;
        } catch (IOException | RuntimeException e) {
            PzCraftMain.LOG.error("could not read or write {}", MAP_FILE, e);
            return null;
        }
    }

    /**
     * The world folder of a PZ save, recorded in the map file the first time: the old shared world if it is there and no save
     * has it yet, otherwise a new folder named after the save.
     */
    static String levelFor(Path mapFile, String key, boolean legacyExists) throws IOException {
        Properties map = new Properties();
        if (Files.isRegularFile(mapFile)) {
            try (Reader r = Files.newBufferedReader(mapFile, StandardCharsets.UTF_8)) { map.load(r); }
        }
        String level = map.getProperty(key);
        if (level != null) return level;
        level = legacyExists && !map.containsValue(LEGACY_LEVEL_ID) ? LEGACY_LEVEL_ID : newLevelId(key);
        map.setProperty(key, level);
        Files.createDirectories(mapFile.getParent());
        try (Writer w = Files.newBufferedWriter(mapFile, StandardCharsets.UTF_8)) {
            map.store(w, "Project-ZombCraft: the Minecraft world folder of each PZ save (<game mode>/<save> = folder)");
        }
        PzCraftMain.LOG.info("PZ save '{}' gets Minecraft world '{}'", key, level);
        return level;
    }

    /** "pzcraft-<save, as letters and digits>-<8 hex of its hash>": readable, and two saves never share one. */
    static String newLevelId(String key) {
        String slug = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) slug = slug.substring(0, 40);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            return "pzcraft-" + slug + "-" + HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    //#if MC_1_20
    //$ private static WorldDimensions voidDimensions(net.minecraft.core.RegistryAccess registries) {
    //#else
    private static WorldDimensions voidDimensions(HolderLookup.Provider registries) {
    //#endif
        HolderGetter<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        HolderGetter<StructureSet> structureSets = registries.lookupOrThrow(Registries.STRUCTURE_SET);
        HolderGetter<PlacedFeature> placedFeatures = registries.lookupOrThrow(Registries.PLACED_FEATURE);
        FlatLevelGeneratorSettings flat = FlatLevelGeneratorSettings.getDefault(biomes, structureSets, placedFeatures)
                .withBiomeAndLayers(List.of(new FlatLayerInfo(1, Blocks.AIR)), Optional.empty(), biomes.getOrThrow(Biomes.PLAINS));
        return WorldPresets.createNormalWorldDimensions(registries).replaceOverworldGenerator(registries, new FlatLevelSource(flat));
    }
}
