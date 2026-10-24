package pzcraft.mc;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelData;
import pzcraft.protocol.RespawnTarget;
import pzcraft.protocol.Wire;

public final class RespawnBridge {
    public static volatile RespawnTarget target;
    public static volatile long acknowledged;
    private static long sequence = System.currentTimeMillis(), lastRetry;
    private static net.minecraft.server.network.ServerGamePacketListenerImpl pendingListener;
    private static net.minecraft.network.protocol.game.ServerboundClientCommandPacket pendingPacket;
    private static boolean replaying;
    private RespawnBridge() {}
    /** Load PZ terrain after the button is pressed, before vanilla tests bed clearance and support. */
    public static boolean prepare(net.minecraft.server.network.ServerGamePacketListenerImpl listener,
                                  net.minecraft.network.protocol.game.ServerboundClientCommandPacket packet) {
        var player=listener.player;
        // single player only: the integrated server holds the respawn until PZ has streamed the destination
        if(replaying||!Session.CLIENT||!Session.pzConnected||!player.level().getServer().isSameThread()
                ||packet.getAction()!=net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN
                ||player.isAlive()||player.wonGame)return false;
        if(pendingListener!=null)return true;
        pendingListener=listener;pendingPacket=packet;
        //#if MC_1_20
        //$ // 1.20.1: the bed/anchor position on the player, else the world spawn (overworld)
        //$ var bed=player.getRespawnPosition();
        //$ var spawnLevel=player.level().getServer().overworld();
        //$ var pos=bed!=null?bed:spawnLevel.getSharedSpawnPos();
        //$ preload(pos.getX()+.5,pos.getY()+.02,pos.getZ()+.5,bed!=null?player.getRespawnAngle():spawnLevel.getSharedSpawnAngle(),0f);
        //#else
        var config=player.getRespawnConfig();
        var data=config==null?player.level().getServer().overworld().getRespawnData():config.respawnData();
        preload(data.pos().getX()+.5,data.pos().getY()+.02,data.pos().getZ()+.5,data.yaw(),data.pitch());
        //#endif
        return true;
    }
    private static void preload(double x,double y,double z,float yaw,float pitch) {
        Session.released=false;
        target=new RespawnTarget(++sequence,x,y,z,yaw,pitch);
        Session.send(Wire.MSG_RESPAWN_TARGET,target.encode());lastRetry=System.currentTimeMillis();
    }
    static void init() {
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, player, alive) -> {
            if (!Session.pzConnected || alive) return;
            Session.released = false;
            Session.pendingTeleport=null;Session.needPlacement=false;
            player.setNoGravity(true);
            player.setDeltaMovement(0, 0, 0);
            target = new RespawnTarget(++sequence, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            Session.send(Wire.MSG_RESPAWN_TARGET, target.encode());
            PzCraftMain.LOG.info("Manual respawn destination {}", target);
        });
    }
    /** Persist the crossover's original spawn once, leaving explicit /setworldspawn changes intact. */
    static void initialSpawn(ServerPlayer player, Session.Teleport tp) {
        var server = player.level().getServer();
        var marker = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("pzcraft-spawn-initialized");
        if (!java.nio.file.Files.exists(marker)) {
            //#if MC_1_20
            //$ server.overworld().setDefaultSpawnPos(BlockPos.containing(tp.x(), tp.y(), tp.z()), tp.yawDeg());
            //#else
            server.setRespawnData(LevelData.RespawnData.of(player.level().dimension(), BlockPos.containing(tp.x(), tp.y(), tp.z()), tp.yawDeg(), tp.pitchDeg()));
            //#endif
            try { java.nio.file.Files.writeString(marker, "Original crossover spawn is stored in level.dat\n"); }
            catch (java.io.IOException e) { PzCraftMain.LOG.error("Could not persist spawn initialization", e); }
        }
    }
    static void tick() {
        var t = target;
        if(pendingListener!=null&&t!=null&&acknowledged==t.sequence()) {
            var player=pendingListener.player;
            //#if MC_1_20
            //$ // 1.20.1: where PlayerList.respawn will put him (alive=true: only looks, uses no anchor charge)
            //$ var bed=player.getRespawnPosition();
            //$ var bedLevel=bed==null?null:player.server.getLevel(player.getRespawnDimension());
            //$ var found=bedLevel==null?java.util.Optional.<net.minecraft.world.phys.Vec3>empty()
            //$         :net.minecraft.world.entity.player.Player.findRespawnPositionAndUseSpawnBlock(bedLevel,bed,player.getRespawnAngle(),player.isRespawnForced(),true);
            //$ var pos=found.orElseGet(()->net.minecraft.world.phys.Vec3.atBottomCenterOf(player.server.overworld().getSharedSpawnPos()));
            //$ float destYaw=found.isPresent()?player.getRespawnAngle():player.server.overworld().getSharedSpawnAngle(), destPitch=0f;
            //#else
            var destination=player.findRespawnPositionAndUseSpawnBlock(false,net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);
            var pos=destination.position();
            //#endif
            if(Math.hypot(pos.x-t.x(),pos.z-t.z())>6||Math.abs(pos.y-t.y())>3) {
                // Missing/blocked beds can fall back to a distant world spawn: stream that too.
                //#if MC_1_20
                //$ preload(pos.x,pos.y,pos.z,destYaw,destPitch);
                //#else
                preload(pos.x,pos.y,pos.z,destination.yRot(),destination.xRot());
                //#endif
            } else {
                var listener=pendingListener;var packet=pendingPacket;
                pendingListener=null;pendingPacket=null;replaying=true;
                try {listener.handleClientCommand(packet);} finally {replaying=false;}
            }
            t=target;
        }
        if (t != null && !Session.released && System.currentTimeMillis() - lastRetry >= 500) {
            lastRetry = System.currentTimeMillis();
            Session.send(Wire.MSG_RESPAWN_TARGET, t.encode());
        }
    }
}
