package pzcraft.mc;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.level.validation.DirectoryValidator;

/** Offers the generated PZ item pack to Minecraft as a required pack, so it is always on. */
public final class PzPackSource implements RepositorySource {
    @Override
    public void loadPacks(Consumer<Pack> result) {
        try {
            Path pack = PzItemPack.refresh();
            if (pack == null) return;
            FolderRepositorySource.discoverPacks(pack.getParent(), new DirectoryValidator(path -> true), (content, resources) -> {
                PackLocationInfo info = new PackLocationInfo("pzcraft/items", Component.literal("PzCraft items"), PackSource.BUILT_IN, Optional.empty());
                Pack created = Pack.readMetaAndCreate(info, resources, PackType.CLIENT_RESOURCES, new PackSelectionConfig(true, Pack.Position.TOP, false));
                if (created != null) result.accept(created);
            });
        } catch (Exception e) {
            PzCraftClient.LOG.warn("PZ item pack unavailable: {}", e.toString());
        }
    }
}

