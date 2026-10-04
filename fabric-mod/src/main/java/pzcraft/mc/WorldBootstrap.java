package pzcraft.mc;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
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

/**
 * Opens (or creates) the hidden void world Steve lives in, straight from the title screen. The world has no terrain:
 * everything Steve stands on comes from PZ's collision field or from blocks the player places.
 */
final class WorldBootstrap {
    static final String LEVEL_ID = "pzcraft";

    private static boolean started;
    private static int idleTicks;

    private WorldBootstrap() {}

    static void tick(Minecraft mc) {
        if (started || mc.level != null || mc.gui.overlay() != null) return;
        Screen screen = mc.gui.screen();
        if (!(screen instanceof TitleScreen || screen instanceof AccessibilityOnboardingScreen)) return;
        if (++idleTicks < 20) return;
        started = true;

        PzCraftClient.LOG.info("Starting hidden world '{}'", LEVEL_ID);
        if (mc.getLevelSource().levelExists(LEVEL_ID)) {
            mc.createWorldOpenFlows().openWorld(LEVEL_ID, () -> mc.setScreenAndShow(new TitleScreen()));
        } else {
            LevelSettings settings = new LevelSettings("PzCraft", GameType.SURVIVAL,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, true), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(LEVEL_ID, settings, new WorldOptions(0L, false, false),
                    WorldBootstrap::voidDimensions, new TitleScreen());
        }
    }

    private static WorldDimensions voidDimensions(HolderLookup.Provider registries) {
        HolderGetter<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        HolderGetter<StructureSet> structureSets = registries.lookupOrThrow(Registries.STRUCTURE_SET);
        HolderGetter<PlacedFeature> placedFeatures = registries.lookupOrThrow(Registries.PLACED_FEATURE);
        FlatLevelGeneratorSettings flat = FlatLevelGeneratorSettings.getDefault(biomes, structureSets, placedFeatures)
                .withBiomeAndLayers(List.of(new FlatLayerInfo(1, Blocks.AIR)), Optional.empty(), biomes.getOrThrow(Biomes.PLAINS));
        return WorldPresets.createNormalWorldDimensions(registries).replaceOverworldGenerator(registries, new FlatLevelSource(flat));
    }
}

