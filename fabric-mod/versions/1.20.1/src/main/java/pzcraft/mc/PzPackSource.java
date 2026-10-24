package pzcraft.mc;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.*;
public final class PzPackSource implements RepositorySource {
    @Override public void loadPacks(Consumer<Pack> result) {
        try {
            var folder=PzItemPack.refresh();if(folder==null)return;
            var resources=FolderRepositorySource.detectPackResources(folder,false);
            if(resources==null)return;
            Pack pack=Pack.readMetaAndCreate("pzcraft/items",Component.literal("Project-ZombCraft items"),true,resources,PackType.CLIENT_RESOURCES,Pack.Position.TOP,PackSource.BUILT_IN);
            if(pack!=null)result.accept(pack);
        }catch(Exception e){PzCraftMain.LOG.error("PZ item pack unavailable",e);}
    }
}
