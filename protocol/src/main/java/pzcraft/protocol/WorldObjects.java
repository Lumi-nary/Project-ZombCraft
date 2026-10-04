package pzcraft.protocol;

import java.nio.*;
import java.util.*;

/** Bounded snapshots of nearby PZ interactable objects and Minecraft light sources. */
public final class WorldObjects {
    private WorldObjects() {}
    public record ObjectFace(int id, int x, int y, int level, int flags) {
        public static final int NORTH=1, WINDOW=2, OPEN=4, CONTAINER=8, BODY=16, FLOOR=32;
    }
    public record Light(int x, int y, int z, int emission, float r, float g, float b) {}
    public static byte[] faces(List<ObjectFace> faces) {
        if (faces.size()>512) throw new IllegalArgumentException("Too many object faces");
        var b=ByteBuffer.allocate(4+faces.size()*20).order(ByteOrder.LITTLE_ENDIAN).putInt(faces.size());
        for(var f:faces)b.putInt(f.id).putInt(f.x).putInt(f.y).putInt(f.level).putInt(f.flags);
        return b.array();
    }
    public static List<ObjectFace> faces(byte[] bytes) {
        var b=read(bytes,20,512); int n=b.getInt(); var out=new ArrayList<ObjectFace>(n);
        for(int i=0;i<n;i++)out.add(new ObjectFace(b.getInt(),b.getInt(),b.getInt(),b.getInt(),b.getInt()));
        return List.copyOf(out);
    }
    public static byte[] lights(List<Light> lights) {
        if(lights.size()>2048)throw new IllegalArgumentException("Too many lights");
        var b=ByteBuffer.allocate(4+lights.size()*28).order(ByteOrder.LITTLE_ENDIAN).putInt(lights.size());
        for(var l:lights)b.putInt(l.x).putInt(l.y).putInt(l.z).putInt(l.emission).putFloat(l.r).putFloat(l.g).putFloat(l.b);
        return b.array();
    }
    public static List<Light> lights(byte[] bytes) {
        var b=read(bytes,28,2048);int n=b.getInt();var out=new ArrayList<Light>(n);
        for(int i=0;i<n;i++)out.add(new Light(b.getInt(),b.getInt(),b.getInt(),b.getInt(),b.getFloat(),b.getFloat(),b.getFloat()));
        return List.copyOf(out);
    }
    private static ByteBuffer read(byte[] bytes,int size,int max) {
        if(bytes.length<4)throw new IllegalArgumentException("Truncated snapshot");
        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);int n=b.getInt(0);
        if(n<0||n>max||bytes.length!=4+n*size)throw new IllegalArgumentException("Invalid snapshot count");
        return b;
    }
}
