package pzcraft.pz;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import zombie.iso.IsoChunk;
/** Small runtime adjustments; original PZ and Viewpoint algorithms remain responsible for loading and meshing. */
public final class Streaming {
    private static Field jobs;
    private static Class<?> sceneType;
    private static Field maskField,sizeField,xField,yField,farField,shellField;
    private static volatile Map<String,Object> draw=Map.of();
    private Streaming() {}
    public static boolean fast() { return SteveControl.drives(zombie.characters.IsoPlayer.getInstance()) && WorldExporter.speed()>10; }
    public static long buildBudget(long original) { return fast()?Math.max(original,12_000_000L):original; }
    public static int compare(IsoChunk a,IsoChunk b,int original) {
        if(!fast())return original;
        double x=WorldExporter.aheadX(),y=WorldExporter.aheadY();
        double da=Math.pow(a.wx*8+4-x,2)+Math.pow(a.wy*8+4-y,2),db=Math.pow(b.wx*8+4-x,2)+Math.pow(b.wy*8+4-y,2);
        return Double.compare(db,da); // WorldStreamer removes the last element after sorting.
    }
    static Map<String,Object> stats() {
        Map<String,Object> s=new LinkedHashMap<>();s.put("fast",fast());s.put("loadedQueue",IsoChunk.loadGridSquare.size());
        s.putAll(draw);
        try {
            if(jobs==null){jobs=zombie.iso.WorldStreamer.class.getDeclaredField("jobQueue");jobs.setAccessible(true);}
            s.put("streamerQueue",((java.util.Collection<?>)jobs.get(zombie.iso.WorldStreamer.instance)).size());
        }catch(ReflectiveOperationException e){s.put("streamerQueue",-1);}
        return s;
    }
    /** Render-thread copy; the control thread never walks Viewpoint's mutable scene or chunk caches. */
    static void scene(Object scene) {
        try {
            Class<?> c=scene.getClass();
            if(c!=sceneType) {
                maskField=c.getField("farMask");sizeField=c.getField("farMaskSize");
                xField=c.getField("farMaskX");yField=c.getField("farMaskY");
                farField=c.getField("farCount");shellField=c.getField("shellCount");sceneType=c;
            }
            byte[] mask=(byte[])maskField.get(scene);
            int size=sizeField.getInt(scene),x=xField.getInt(scene),y=yField.getInt(scene);
            int cx=(int)Math.floor(WorldExporter.x()/8),cy=(int)Math.floor(WorldExporter.y()/8);
            java.util.ArrayList<Integer> ahead=new java.util.ArrayList<>();
            double speed=WorldExporter.speed(),vx=WorldExporter.velocityX(),vy=WorldExporter.velocityY();
            for(int i=0;i<=6;i++) {
                int ax=cx+(int)Math.round(speed>1?vx/speed*i:0)-x,ay=cy+(int)Math.round(speed>1?vy/speed*i:0)-y;
                ahead.add(ax>=0&&ay>=0&&ax<size&&ay<size?(mask[ay*size+ax]&255):-1);
            }
            draw=Map.of("nearGroundMaskAhead",java.util.List.copyOf(ahead),"farCells",farField.getInt(scene),
                    "groundShells",shellField.getInt(scene));
        }catch(ReflectiveOperationException e){draw=Map.of("sceneDiagnostics",e.toString());}
    }
}

