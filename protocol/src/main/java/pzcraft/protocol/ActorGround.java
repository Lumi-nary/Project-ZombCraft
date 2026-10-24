package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Ground-proxy physics feedback. Desired coordinates let PZ reject corrections for a stale/teleported actor. */
public final class ActorGround {
    private ActorGround() {}
    public record Pose(int id, double desiredX, double desiredZ, double x, double y, double z) {}
    private static void validate(Pose p) {
        if (p.id < 0 || !Double.isFinite(p.desiredX) || !Double.isFinite(p.desiredZ)
                || !Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z)
                || p.y < -48 || p.y > 64 || Math.hypot(p.x-p.desiredX,p.z-p.desiredZ)>2)
            throw new IllegalArgumentException("Invalid ground actor pose");
    }
    public static byte[] encode(List<Pose> poses) {
        if (poses.size()>120) throw new IllegalArgumentException("Too many ground actors");
        var b=ByteBuffer.allocate(4+44*poses.size()).order(ByteOrder.LITTLE_ENDIAN).putInt(poses.size());
        for(var p:poses){validate(p);b.putInt(p.id).putDouble(p.desiredX).putDouble(p.desiredZ)
                .putDouble(p.x).putDouble(p.y).putDouble(p.z);}
        return b.array();
    }
    public static List<Pose> decode(byte[] bytes) {
        if(bytes.length<4)throw new IllegalArgumentException("Truncated ground actors");
        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);int n=b.getInt();
        if(n<0||n>120||bytes.length!=4+44*n)throw new IllegalArgumentException("Invalid ground actors");
        var poses=new ArrayList<Pose>(n);
        for(int i=0;i<n;i++){var p=new Pose(b.getInt(),b.getDouble(),b.getDouble(),b.getDouble(),b.getDouble(),b.getDouble());validate(p);poses.add(p);}
        return List.copyOf(poses);
    }
}
