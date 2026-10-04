package pzcraft.pz;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.*;
import zombie.characters.IsoPlayer;
import zombie.iso.*;
import zombie.iso.objects.*;

final class InteractionBridge {
    private static final IdentityHashMap<Object,Integer> ids=new IdentityHashMap<>();
    private static final Map<Integer,IsoObject> objects=new HashMap<>();
    private static final Map<Integer,Object> others=new HashMap<>();
    private record Request(int id,int action){}
    private static final ConcurrentLinkedQueue<Request> clicks=new ConcurrentLinkedQueue<>();
    private static int nextId;
    private static long lastSent;
    /** The object behind a runtime id handed to Minecraft in the last snapshot (game thread). */
    static IsoObject objectById(int id) {return objects.get(id);}
    /** Whatever Minecraft was shown with this id: an object, a corpse, or a tile with items on its floor. */
    static Object targetById(int id) {Object o=objects.get(id);return o!=null?o:others.get(id);}
    static void receive(int id,int action) {if(clicks.size()<128)clicks.add(new Request(id,action));}
    static void tick(IsoPlayer player) {
        if(player==null||!LinkService.connected())return;
        Request request;
        while((request=clicks.poll())!=null) {
            IsoObject object=objects.get(request.id());
            if(object==null||object.getSquare()==null)continue;
            var sq=object.getSquare();
            Log.info("Interact request "+request.id()+" action="+request.action()+" object="+object.getClass().getSimpleName()+" at "+sq.x+","+sq.y);
            if(request.action()==0&&(Math.hypot(sq.x+.5-player.getX(),sq.y+.5-player.getY())>6||Math.abs(sq.z-player.getZ())>1))continue;
            if(object instanceof IsoDoor door){if(request.action()==0||door.IsOpen()!=(request.action()==1))door.ToggleDoor(player);}
            else if(object instanceof IsoThumpable door&&door.isDoor()){if(request.action()==0||door.IsOpen()!=(request.action()==1))door.ToggleDoor(player);}
            else if(object instanceof IsoWindow window)window.ToggleWindow(player);
            WorldExporter.changed();
        }
        long now=System.currentTimeMillis();if(now-lastSent<200)return;lastSent=now;
        var cell=IsoWorld.instance.getCell();if(cell==null)return;
        var faces=new ArrayList<WorldObjects.ObjectFace>();
        var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        objects.clear();others.clear();int px=(int)player.getX(),py=(int)player.getY(),pz=(int)player.getZ();
        for(int x=px-8;x<=px+8;x++)for(int y=py-8;y<=py+8;y++)for(int z=pz-1;z<=pz+1;z++) {
            var sq=cell.getGridSquare(x,y,z);if(sq==null)continue;
            // Containers within reach (fridges, crates, shelves...): Minecraft opens them as chest screens.
            if(Math.abs(x-px)<=6&&Math.abs(y-py)<=6&&z==pz) {
                var all=sq.getObjects();
                for(int n=0;n<all.size();n++) {
                    var object=all.get(n);
                    if(object==null||object.getContainerCount()<=0||faces.size()>=512)continue;
                    seen.add(object);int oid=ids.computeIfAbsent(object,o->++nextId);objects.put(oid,object);
                    faces.add(new WorldObjects.ObjectFace(oid,x,y,z,WorldObjects.ObjectFace.CONTAINER));
                }
                for(var body:sq.getDeadBodys()) {
                    if(body==null||body.getContainer()==null||faces.size()>=512)continue;
                    seen.add(body);int oid=ids.computeIfAbsent(body,o->++nextId);others.put(oid,body);
                    faces.add(new WorldObjects.ObjectFace(oid,x,y,z,WorldObjects.ObjectFace.CONTAINER|WorldObjects.ObjectFace.BODY));
                }
                if(!sq.getWorldObjects().isEmpty()&&faces.size()<512) {
                    seen.add(sq);int oid=ids.computeIfAbsent(sq,o->++nextId);others.put(oid,sq);
                    faces.add(new WorldObjects.ObjectFace(oid,x,y,z,WorldObjects.ObjectFace.CONTAINER|WorldObjects.ObjectFace.FLOOR));
                }
            }
            for(var object:sq.getSpecialObjects()) {
                boolean north,open;int flags=0;
                if(object instanceof IsoDoor d){north=d.getNorth();open=d.IsOpen();}
                else if(object instanceof IsoThumpable d&&d.isDoor()){north=d.getNorth();open=d.IsOpen();}
                else if(object instanceof IsoWindow w){north=w.getNorth();open=w.IsOpen();flags|=WorldObjects.ObjectFace.WINDOW;}
                else continue;
                if(faces.size()>=512)break;
                if(north)flags|=WorldObjects.ObjectFace.NORTH;if(open)flags|=WorldObjects.ObjectFace.OPEN;
                seen.add(object);int oid=ids.computeIfAbsent(object,o->++nextId);objects.put(oid,object);
                faces.add(new WorldObjects.ObjectFace(oid,x,y,z,flags));
            }
        }
        ids.keySet().retainAll(seen);
        LinkService.send(Wire.MSG_OBJECTS,WorldObjects.faces(faces));
    }
}

