package pzcraft.mc;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 1.20.1 uses CustomModelData overrides; its generated pack is separate from the 26.x item-definition pack. */
public final class PzItemPack {
    private static String providedStamp;
    private static final Map<String,Integer> ids=new TreeMap<>();
    private static final Path pack=PzItemCatalog.DIR.resolve("packs/pzcraft-items-1.20.1");
    private static final Path idFile=pack.resolve("model-ids.json");
    private static boolean loaded;
    private static final Gson GSON=new Gson();
    public static synchronized int modelId(String icon) {
        String key=PzItemCatalog.iconKey(icon);
        if(!loaded) {
            loaded=true;
            if(Files.isRegularFile(idFile))try {
                JsonParser.parseString(Files.readString(idFile)).getAsJsonObject().entrySet().forEach(e->ids.put(e.getKey(),e.getValue().getAsInt()));
            }catch(Exception e){throw new IllegalStateException("Cannot read stable 1.20.1 model IDs",e);}
        }
        return ids.computeIfAbsent(key,k->{int id=1+(k.hashCode()&0x7fffff);while(ids.containsValue(id))id=id%0xfffffe+1;return id;});
    }
    public static String currentStamp() {
        try {
            Path catalogue=PzItemCatalog.DIR.resolve("items.json"),icons=PzItemCatalog.DIR.resolve("icons");
            if(!Files.isRegularFile(catalogue)||!Files.isDirectory(icons))return null;
            long count;try(var s=Files.list(icons)){count=s.filter(p->p.toString().endsWith(".png")).count();}
            return "1201-v2:"+GunModels.stamp()+":"+Files.getLastModifiedTime(catalogue).toMillis()+":"+Files.size(catalogue)+":"+count;
        }catch(IOException e){return null;}
    }
    public static boolean stale(){String s=currentStamp();return s!=null && !s.equals(providedStamp);}
    public static synchronized Path refresh() throws IOException {
        String stamp=currentStamp();if(stamp==null)return null;
        Path stampFile=pack.resolve(".stamp");
        if(Files.isRegularFile(stampFile) && Files.readString(stampFile).equals(stamp)) {providedStamp=stamp;return pack;}
        Path assets=pack.resolve("assets/project-zombcraft"),textures=assets.resolve("textures/item/pz"),models=assets.resolve("models/item/pz");
        Files.createDirectories(textures);Files.createDirectories(models);
        Files.writeString(pack.resolve("pack.mcmeta"),"{\"pack\":{\"pack_format\":15,\"description\":\"Project-ZombCraft items (1.20.1)\"}}");
        JsonArray overrides=new JsonArray();
        List<Path> icons;try(var stream=Files.list(PzItemCatalog.DIR.resolve("icons"))) {icons=stream.filter(p->p.toString().endsWith(".png")).sorted().toList();}
        TreeMap<Integer,String> ordered=new TreeMap<>();
        for(Path png:icons) {
            String name=png.getFileName().toString(),key=PzItemCatalog.iconKey(name.substring(0,name.length()-4));
            Files.copy(png,textures.resolve(key+".png"),StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(models.resolve(key+".json"),"{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"project-zombcraft:item/pz/"+key+"\"}}");
            ordered.put(modelId(key),key);
        }
        ordered.forEach((id,key)->{
            JsonObject o=new JsonObject(),predicate=new JsonObject();predicate.addProperty("custom_model_data",id);o.add("predicate",predicate);
            o.addProperty("model","project-zombcraft:item/pz/"+key);overrides.add(o);
        });
        for(String item:new String[]{"pz_item","pz_tile"}) {
            JsonObject root=new JsonObject();root.addProperty("parent","minecraft:item/generated");
            JsonObject tex=new JsonObject();tex.addProperty("layer0","minecraft:item/paper");root.add("textures",tex);root.add("overrides",overrides);
            Files.writeString(assets.resolve("models/item/"+item+".json"),GSON.toJson(root));
        }
        GunModels.writePack(pack);
        Files.writeString(idFile,GSON.toJson(ids));Files.writeString(stampFile,stamp);providedStamp=stamp;
        PzCraftMain.LOG.info("PZ 1.20.1 item pack: {} icons, stable CustomModelData IDs",icons.size());return pack;
    }
    private PzItemPack(){}
}
