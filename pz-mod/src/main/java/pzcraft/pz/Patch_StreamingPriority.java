package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import me.zed_0xff.zombie_buddy.Patch.Return;
import zombie.iso.IsoChunk;
@Patch(className="zombie.iso.WorldStreamer$ChunkComparator",methodName="compare")
public class Patch_StreamingPriority {
    @OnExit public static void exit(@Argument(0) IsoChunk a,@Argument(1) IsoChunk b,@Return(readOnly=false) int result) {
        result=Streaming.compare(a,b,result);
    }
}

