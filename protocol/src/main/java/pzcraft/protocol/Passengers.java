package pzcraft.protocol;

import java.nio.*;
import java.util.*;

/** Full snapshot; omitted riders are released, vehicle 0 supplies the final dismount pose. */
public final class Passengers {
    private Passengers(){}
    public record Rider(int actorId,int vehicleId,double x,double y,double z,float yaw){}
    public static byte[] encode(List<Rider> riders){
        if(riders.size()>256)throw new IllegalArgumentException("Too many riders");
        var b=ByteBuffer.allocate(4+36*riders.size()).order(ByteOrder.LITTLE_ENDIAN).putInt(riders.size());
        for(var r:riders)b.putInt(r.actorId).putInt(r.vehicleId).putDouble(r.x).putDouble(r.y).putDouble(r.z).putFloat(r.yaw);
        return b.array();
    }
    public static List<Rider> decode(byte[] bytes){
        if(bytes.length<4)throw new IllegalArgumentException("Truncated passengers");
        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);int n=b.getInt();
        if(n<0||n>256||bytes.length!=4+36*n)throw new IllegalArgumentException("Invalid passengers");
        var out=new ArrayList<Rider>(n);for(int i=0;i<n;i++)out.add(new Rider(b.getInt(),b.getInt(),b.getDouble(),b.getDouble(),b.getDouble(),b.getFloat()));
        return List.copyOf(out);
    }
}
