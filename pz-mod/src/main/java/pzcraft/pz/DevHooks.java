package pzcraft.pz;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import zombie.characters.IsoPlayer;

/**
 * Development commands. They can be written one per line into {@code <Zomboid cache>/Lua/pzcraft-dev.txt} (run once
 * when the file changes) or sent through the control API ({@code POST /cmd}).
 *
 * <pre>
 *   spawn [count] [distance]   spawn zombies that far in front of the player (default 1 at 4 tiles)
 *   hurt [pzHealth]            take PZ body damage (default 10)
 *   tp dx dy                   move the PZ player by an offset and re-place Steve
 *   pull [distance]            put the nearest zombie in front of Steve (default 1.8 tiles)
 *   hunger [0..1]              set PZ hunger (1 = starving)
 *   where                      report the player's PZ position and square
 *   mc &lt;command&gt;               run a Minecraft command as Steve (test setup: give, effect, time...)
 *   third [on|off]             Minecraft rear/first camera in Steve mode; otherwise Viewpoint's shoulder camera
 *   camera 0|1|2               Minecraft first/rear/front perspective
 *   weather kind [i] [hours]   PZ weather as a Minecraft /weather command would set it (clear, rain, thunder, drizzle,
 *                              showers, heavy, storm, tropical, blizzard, snow, fog)
 *   eye steady|follow          hold Viewpoint's eye through PZ animations while Steve drives (default steady)
 * </pre>
 */
final class DevHooks {
    private static long lastModified = -2; // -2: not looked yet; -1: file did not exist at start
    private static long lastCheck;

    private DevHooks() {}

    static void poll() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 1000) return;
        lastCheck = now;
        try {
            Path f = Paths.get(zombie.ZomboidFileSystem.instance.getCacheDir(), "Lua", "pzcraft-dev.txt");
            boolean exists = Files.isRegularFile(f);
            long m = exists ? Files.getLastModifiedTime(f).toMillis() : -1;
            if (lastModified == -2) { lastModified = m; return; } // ignore whatever was there when the game started
            if (!exists || m == lastModified) return;
            lastModified = m;
            for (String line : Files.readAllLines(f)) Log.info("dev: " + exec(line.trim()));
        } catch (Throwable t) {
            Log.error("dev hooks", t);
        }
    }

    /** Runs one command on the game thread and returns what it did. */
    static String exec(String line) {
        try {
            return run(line);
        } catch (RuntimeException e) {
            return "error: " + e;
        }
    }

    private static String run(String line) {
        if (line.isEmpty() || line.startsWith("#")) return "(nothing)";
        String[] a = line.split("\\s+");
        IsoPlayer p = PuppetDriver.localPlayer();
        if (p == null) return "no player yet";
        switch (a[0]) {
            case "workshop" -> {
                if (a.length < 2) return "usage: workshop id [download]";
                long id = Long.parseLong(a[1]);
                var workshop = zombie.core.znet.SteamWorkshop.instance;
                if (a.length > 2 && "download".equals(a[2])) {
                    var callback = (zombie.core.znet.ISteamWorkshopCallback) java.lang.reflect.Proxy.newProxyInstance(
                            DevHooks.class.getClassLoader(), new Class<?>[] {zombie.core.znet.ISteamWorkshopCallback.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("equals")) return proxy == args[0];
                                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                                if (method.getName().equals("toString")) return "PzCraftWorkshopDownload";
                                Log.info("workshop " + id + ": " + method.getName()); return null;
                            });
                    return "subscribe=" + workshop.SubscribeItem(id, callback) + " download=" + workshop.DownloadItem(id, true, callback);
                }
                return "state=" + workshop.GetItemState(id) + " folder=" + workshop.GetItemInstallFolder(id);
            }
            case "view" -> {
                if (a.length != 2) return "usage: view on|off";
                boolean ok = "on".equals(a[1]) ? ViewpointBridge.enableView() : "off".equals(a[1]) && ViewpointBridge.disableView();
                return ok ? "view " + a[1] : "view unchanged";
            }
            case "lightprobe" -> {
                if(a.length!=4)return "usage: lightprobe pzX pzY level";
                try {
                    var method=Class.forName("viewpoint.light.OwnedLight").getMethod("lightAt",int.class,int.class,int.class);
                    int x=Integer.parseInt(a[1]),y=Integer.parseInt(a[2]),z=Integer.parseInt(a[3]);
                    return String.valueOf(method.invoke(null,x,y,z));
                }catch(ReflectiveOperationException e){return "error: "+e;}
            }
            case "spawn" -> {
                int count = a.length > 1 ? Integer.parseInt(a[1]) : 1;
                double dist = a.length > 2 ? Double.parseDouble(a[2]) : 4;
                double yaw = Math.toRadians(pzcraft.protocol.Coords.pzRadToMcYawDeg(ViewpointBridge.yaw()) + 90.0);
                int x = (int) Math.floor(p.getX() + Math.cos(yaw) * dist);
                int y = (int) Math.floor(p.getY() + Math.sin(yaw) * dist);
                int z = (int) Math.floor(p.getZ());
                zombie.Lua.LuaManager.GlobalObject.addZombiesInOutfit(x, y, z, count, null, 50);
                return "spawned " + count + " zombie(s) at " + x + "," + y + "," + z;
            }
            case "hurt" -> {
                float v = a.length > 1 ? Float.parseFloat(a[1]) : 10f;
                p.getBodyDamage().ReduceGeneralHealth(v);
                return "hurt " + v + " -> health " + p.getBodyDamage().getOverallBodyHealth();
            }
            case "heal" -> {
                LinkService.send(pzcraft.protocol.Wire.MSG_DEV_HEAL, new byte[0]);
                return "requested Minecraft test heal";
            }
            case "block" -> {
                if (a.length != 5) return "usage: block x y z minecraft:block_id";
                for (int i = 1; i <= 3; i++) Integer.parseInt(a[i]);
                LinkService.send(pzcraft.protocol.Wire.MSG_DEV_BLOCK, String.join(" ", java.util.Arrays.copyOfRange(a, 1, 5))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return "requested Minecraft test block";
            }
            case "pull" -> {
                double dist = a.length > 1 ? Double.parseDouble(a[1]) : 1.8;
                double yaw = Math.toRadians(pzcraft.protocol.Coords.pzRadToMcYawDeg(ViewpointBridge.yaw()) + 90.0);
                zombie.characters.IsoZombie best = null;
                double bd = 1e9;
                for (zombie.characters.IsoZombie z : zombie.iso.IsoWorld.instance.getCell().getZombieList()) {
                    double d = Math.hypot(z.getX() - p.getX(), z.getY() - p.getY());
                    if (!z.isDead() && d < bd) { bd = d; best = z; }
                }
                if (best == null) return "no zombie to pull";
                best.setForceX((float) (p.getX() + Math.cos(yaw) * dist));
                best.setForceY((float) (p.getY() + Math.sin(yaw) * dist));
                best.setZ(p.getZ());
                return "pulled zombie " + best.getID() + " to " + dist + " tiles in front";
            }
            case "tp" -> {
                double dx = a.length > 1 ? Double.parseDouble(a[1]) : 0, dy = a.length > 2 ? Double.parseDouble(a[2]) : 0;
                p.teleportTo((float) (p.getX() + dx), (float) (p.getY() + dy), (int) Math.floor(p.getZ()));
                PuppetDriver.requestReplace();
                return "teleported player by " + dx + "," + dy;
            }
            case "hunger" -> {
                float v = a.length > 1 ? Float.parseFloat(a[1]) : 0.5f;
                p.getStats().set(zombie.characters.CharacterStat.HUNGER, v);
                return "hunger set to " + v;
            }
            case "mc" -> {
                if (a.length < 2) return "usage: mc <minecraft command>";
                String command = line.substring(2).trim();
                LinkService.send(pzcraft.protocol.Wire.MSG_DEV_COMMAND, command.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return "sent Minecraft command: " + command;
            }
            case "eye" -> {
                SteveEye.enabled = a.length < 2 || !a[1].equalsIgnoreCase("follow");
                return SteveEye.enabled ? "eye held steady through PZ animations while Steve drives" : "eye follows PZ animations (Viewpoint default)";
            }
            case "weather" -> {
                // weather <clear|rain|thunder|drizzle|showers|heavy|storm|tropical|blizzard|snow|fog> [intensity] [hours]
                int kind = a.length > 1 ? pzcraft.protocol.Weather.NAMES.indexOf(a[1].toLowerCase()) : -1;
                if (kind < 0) return "usage: weather " + String.join("|", pzcraft.protocol.Weather.NAMES) + " [intensity 0..1] [hours]";
                float intensity = a.length > 2 ? Float.parseFloat(a[2]) : -1f, hours = a.length > 3 ? Float.parseFloat(a[3]) : 0f;
                WeatherBridge.receive(new pzcraft.protocol.Weather.Command(kind, intensity, hours));
                return "weather " + a[1] + " queued";
            }
            case "third" -> {
                boolean on = a.length < 2 || !a[1].equalsIgnoreCase("off");
                if (SteveControl.ownsSurvival(p)) return GuiInput.camera(on ? 1 : 0);
                return ViewpointBridge.setThirdPerson(on) ? "third person " + (on ? "on" : "off") : "Viewpoint third person not available";
            }
            case "camera" -> { return a.length == 2 ? GuiInput.camera(Integer.parseInt(a[1])) : "usage: camera 0|1|2"; }
            case "mcwindow" -> {return GuiInput.mcWindow(a.length<2 || !a[1].equals("minimize"));}
            case "terrain" -> {WorldExporter.changed();return "collision columns queued for refresh";}
            case "items" -> {return a.length >= 2 && a[1].equals("export") ? ItemExporter.export() : "usage: items export";}
            case "warp" -> {
                Overlay.warpEnabled = a.length < 2 || !a[1].equalsIgnoreCase("off");
                return "rotation warp " + (Overlay.warpEnabled ? "on" : "off");
            }
            case "native" -> {
                NativeBlocks.setEnabled(a.length < 2 || !a[1].equalsIgnoreCase("off"));
                return "native blocks: " + NativeBlocks.stats();
            }
            case "handlight" -> {   // the first-person hand's brightness against Viewpoint's picture (1 = PZ's light as it is)
                if (a.length > 1) NativeEntities.handGain = Math.max(0.1f, Math.min(4f, Float.parseFloat(a[1])));
                return "hand light: " + NativeEntities.handLightStats();
            }
            case "shadows" -> {
                NativeBlocks.setShadows(a.length < 2 || !a[1].equalsIgnoreCase("off"));
                return "native blocks: " + NativeBlocks.stats();
            }
            case "spin" -> {
                float dps = a.length > 1 ? Float.parseFloat(a[1]) : 90f;
                long ms = a.length > 2 ? Long.parseLong(a[2]) : 3000L;
                InputOverride.spin(dps, ms);
                return "spinning " + dps + " deg/s for " + ms + " ms";
            }
            case "look" -> {
                InputOverride.look(a.length > 1 ? Float.parseFloat(a[1]) : null, a.length > 2 ? Float.parseFloat(a[2]) : null, false);
                return "look set to yaw " + (a.length > 1 ? a[1] : "-") + " pitch " + (a.length > 2 ? a[2] : "-");
            }
            case "overlay" -> {
                // overlay shader|ff, overlay pollute 0|1 (test hooks for the drawing path)
                if (a.length > 1 && a[1].equals("ff")) Overlay.useShader = false;
                else if (a.length > 1 && a[1].equals("shader")) Overlay.useShader = true;
                else if (a.length > 2 && a[1].equals("pollute")) Overlay.pollute = Integer.parseInt(a[2]);
                return "overlay: shader=" + Overlay.useShader + " pollute=" + Overlay.pollute;
            }
            case "where" -> {
                return String.format("player at (%.2f, %.2f, %.3f) square=%s health=%.1f",
                        p.getX(), p.getY(), p.getZ(), p.getCurrentSquare(), p.getBodyDamage().getOverallBodyHealth());
            }
            default -> {
                return "unknown command '" + line + "' (spawn, hurt, tp, pull, hunger, warp on|off, spin dps ms, look yaw pitch, where, mc <command>, third on|off, eye steady|follow)";
            }
        }
    }
}
