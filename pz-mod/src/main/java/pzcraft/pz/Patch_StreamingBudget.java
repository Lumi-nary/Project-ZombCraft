package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Return;
@Patch(className="viewpoint.world.ChunkBuilds",methodName="buildBudget")
public class Patch_StreamingBudget {
    @OnExit public static void exit(@Return(readOnly=false) long result) {result=Streaming.buildBudget(result);}
}

