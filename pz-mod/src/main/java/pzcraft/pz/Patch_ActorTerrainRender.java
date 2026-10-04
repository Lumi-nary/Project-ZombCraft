package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.OnExit;

/** Scope the physical height to native model/blob/shadow capture, including exceptional exits. */
@Patch(className="viewpoint.models.Characters",methodName="snapshot")
public class Patch_ActorTerrainRender {
    @OnEnter public static void enter(){ActorTerrain.beginRender();}
    @OnExit(onThrowable=Throwable.class) public static void exit(){ActorTerrain.endRender();}
}

