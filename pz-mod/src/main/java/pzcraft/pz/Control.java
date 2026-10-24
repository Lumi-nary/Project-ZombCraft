package pzcraft.pz;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.lwjgl.opengl.GL11;
import org.lwjgl.BufferUtils;
import pzcraft.protocol.PlayerState;
import zombie.characters.IsoPlayer;

/**
 * Local control API for automation and tests: JSON over HTTP on 127.0.0.1 (default port 47600), protected by a token
 * in {@code <Zomboid cache>/Lua/pzcraft-control.token}. Lets a tool read exact game state, inject input, run dev
 * commands and Lua, press menu buttons and take screenshots of what PZ actually rendered, without screen scraping or
 * stealing the user's mouse and keyboard.
 *
 * <pre>
 *   GET  /state                      game, link, player, Steve, overlay and fps
 *   GET  /screenshot?scale=0.5       PNG of PZ's final frame
 *   POST /input   {"hold":["FWD"],"ms":1500,"look":{"yaw":10,"pitch":-5,"relative":true},"hotbar":3,"wait":true}
 *   POST /cmd     {"line":"tp 9 9"}      dev commands (see DevHooks)
 *   POST /lua     {"code":"return 1+1"} run Lua on the game thread
 *   POST /menu    {"action":"continue"}  main-menu actions
 * </pre>
 */
public final class Control {
    private static final ConcurrentLinkedQueue<FutureTask<?>> tasks = new ConcurrentLinkedQueue<>();
    private static volatile CompletableFuture<byte[]> screenshot;
    private static volatile double screenshotScale = 1.0;
    private static volatile String shotLook = "";
    private static String token;
    private static HttpServer server;
    private static final long started = System.currentTimeMillis();

    // frame statistics (render thread writes)
    private static volatile long frames;
    private static long fpsMark = System.nanoTime();
    private static long fpsFrames;
    private static volatile double fps;

    private Control() {}

    // ---------------------------------------------------------------------------------------------------------
    // lifecycle

    static void start() {
        Thread t = new Thread(Control::run, "pzcraft-control");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        try {
            Thread.sleep(3000); // the cache dir is known by now
            Path dir = Paths.get(zombie.ZomboidFileSystem.instance.getCacheDir(), "Lua");
            Files.createDirectories(dir);
            Path cfg = dir.resolve("pzcraft.properties");
            int port = 47600;
            // Only on a development machine, which has pzcraft.properties (a player's install has none): the API runs Lua and
            // drives input, so nothing should listen for it on a player's computer. control.enabled=false turns it off there too.
            boolean enabled = false;
            if (Files.isRegularFile(cfg)) {
                java.util.Properties p = new java.util.Properties();
                try (var in = Files.newInputStream(cfg)) { p.load(in); }
                port = Integer.parseInt(p.getProperty("control.port", "47600").trim());
                enabled = !"false".equalsIgnoreCase(p.getProperty("control.enabled", "true").trim());
            }
            if (!enabled) { Log.info("control API off (" + (Files.isRegularFile(cfg) ? "control.enabled=false" : "no " + cfg) + ")"); return; }
            Path tok = dir.resolve("pzcraft-control.token");
            token = Files.isRegularFile(tok) ? Files.readString(tok).trim() : null;
            if (token == null || token.length() < 16) {
                token = UUID.randomUUID().toString().replace("-", "");
                Files.writeString(tok, token);
            }
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 8);
            server.createContext("/", Control::handle);
            ExecutorService ex = Executors.newFixedThreadPool(3, r -> { Thread th = new Thread(r, "pzcraft-control-http"); th.setDaemon(true); return th; });
            server.setExecutor(ex);
            server.start();
            Log.info("control API on http://127.0.0.1:" + port + " (token in " + tok + ")");
        } catch (Throwable t) {
            Log.error("control API failed to start", t);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // game-thread plumbing

    /** Game thread, once per frame: run queued tasks and apply look changes. */
    static void drain() {
        FutureTask<?> t;
        int n = 0;
        while ((t = tasks.poll()) != null && n++ < 50) t.run();
        InputOverride.applyLook();
    }

    private static <T> T onGameThread(Callable<T> c) throws Exception {
        FutureTask<T> f = new FutureTask<>(c);
        tasks.add(f);
        return f.get(8, TimeUnit.SECONDS);
    }

    /** Render thread, once per presented frame (from the Display.update hook, after the overlay is drawn). */
    static void afterFrame(int w, int h) {
        frames++;
        long now = System.nanoTime();
        fpsFrames++;
        if (now - fpsMark >= 1_000_000_000L) {
            fps = fpsFrames * 1e9 / (now - fpsMark);
            fpsFrames = 0;
            fpsMark = now;
        }
        CompletableFuture<byte[]> f = screenshot;
        if (f != null && !f.isDone()) {
            screenshot = null;
            try {
                shotLook = String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f,%.3f",
                        pzcraft.protocol.Coords.pzRadToMcYawDeg(ViewpointBridge.yaw()), pzcraft.protocol.Coords.pzPitchRadToMcDeg(ViewpointBridge.pitch()),
                        Overlay.lastWarpYaw(), Overlay.lastWarpPitch());
                f.complete(grab(w, h, screenshotScale));
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        }
    }

    private static byte[] grab(int w, int h, double scale) throws IOException {
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glReadBuffer(GL11.GL_BACK);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int dst = h - 1 - y; // GL rows are bottom-first
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                row[x] = ((buf.get(i) & 0xFF) << 16) | ((buf.get(i + 1) & 0xFF) << 8) | (buf.get(i + 2) & 0xFF);
            }
            img.setRGB(0, dst, w, 1, row, 0, w);
        }
        if (scale > 0.05 && scale < 0.999) {
            int nw = Math.max(1, (int) (w * scale)), nh = Math.max(1, (int) (h * scale));
            BufferedImage small = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            var g = small.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, nw, nh, null);
            g.dispose();
            img = small;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------------------
    // HTTP

    private static void handle(HttpExchange ex) throws IOException {
        try {
            if (!token.equals(ex.getRequestHeaders().getFirst("X-Token"))) {
                send(ex, 401, "text/plain", "bad or missing X-Token".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            switch (path) {
                case "/state" -> json(ex, 200, onGameThread(Control::state));
                case "/screenshot" -> screenshot(ex, query);
                case "/input" -> json(ex, 200, input(JsonLite.object(body)));
                case "/gui" -> json(ex, 200, onGameThread(() -> gui(JsonLite.object(body))));
                case "/cmd" -> {
                    Map<String, Object> req = JsonLite.object(body);
                    String line = String.valueOf(req.get("line"));
                    String result = onGameThread(() -> DevHooks.exec(line));
                    json(ex, 200, Map.of("ok", true, "result", result));
                }
                case "/lua" -> {
                    String code = String.valueOf(JsonLite.object(body).get("code"));
                    json(ex, 200, onGameThread(() -> lua(code)));
                }
                case "/menu" -> {
                    String action = String.valueOf(JsonLite.object(body).get("action"));
                    json(ex, 200, onGameThread(() -> menu(action)));
                }
                default -> send(ex, 404, "text/plain", ("no such endpoint " + path).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException e) {
            json(ex, 400, Map.of("ok", false, "error", String.valueOf(e.getMessage())));
        } catch (Throwable t) {
            json(ex, 500, Map.of("ok", false, "error", String.valueOf(t)));
        } finally {
            ex.close();
        }
    }

    private static void json(HttpExchange ex, int code, Object o) throws IOException {
        send(ex, code, "application/json", JsonLite.write(o).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private static void screenshot(HttpExchange ex, String query) throws Exception {
        double scale = 1.0;
        if (query != null) for (String kv : query.split("&")) if (kv.startsWith("scale=")) scale = Double.parseDouble(kv.substring(6));
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        screenshotScale = scale;
        screenshot = f;
        byte[] png = f.get(8, TimeUnit.SECONDS);
        ex.getResponseHeaders().set("X-Look", shotLook); // yaw,pitch (Minecraft degrees) at the frame, then the warp applied to the blocks
        send(ex, 200, "image/png", png);
    }

    // ---------------------------------------------------------------------------------------------------------
    // endpoints

    private static Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uptimeSec", (System.currentTimeMillis() - started) / 1000);
        m.put("frames", frames);
        m.put("fps", Math.round(fps * 10) / 10.0);
        String gs = zombie.GameWindow.states != null && zombie.GameWindow.states.current != null
                ? zombie.GameWindow.states.current.getClass().getSimpleName() : "?";
        m.put("gameState", gs);
        IsoPlayer p = IsoPlayer.getInstance();
        m.put("inGame", p != null);
        m.put("paused", zombie.GameTime.isGamePaused());
        m.put("pzPaused", PauseFreeze.pzPaused());   // also counts the in-game Esc menu (PauseFreeze)
        m.put("ingameMenu", PauseFreeze.ingameMenuOpen());
        m.put("minecraftGui", GuiInput.status());
        var time = zombie.GameTime.getInstance();
        m.put("clock", Map.of("hour", time.getTimeOfDay(), "year", time.getYear(), "month", time.getMonth()+1, "day", time.getDay()+1));
        m.put("minecraftLights", LightBridge.count());
        m.put("minecraftPassengers", PassengerBridge.count());
        m.put("actorTerrain", ActorTerrain.stats());
        m.put("nativePathfinding", zombie.pathfind.nativeCode.PathfindNative.useNativeCode);
        m.put("blastRagdolls", BlastRagdolls.stats());
        Map<String, Object> vp = new LinkedHashMap<>();
        vp.put("available", ViewpointBridge.available());
        vp.put("view3d", ViewpointBridge.viewEnabled());
        vp.put("captured", ViewpointBridge.captured());
        vp.put("thirdPerson", ViewpointBridge.thirdPerson());
        vp.put("minecraftCamera", SteveCamera.stats());
        float[] fr = ViewpointBridge.latestFrame();
        if (fr != null) vp.put("eye", List.of(fr[3], fr[4], fr[5])); // relative to the camera; [1] is Steve's eye height too
        vp.put("steadyEyeHeldFrames", SteveEye.heldFrames());
        m.put("viewpoint", vp);
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("connected", LinkService.connected());
        link.put("ready", LinkService.minecraftReady());
        link.put("rttMs", LinkService.rttMs());
        link.put("minecraftPid", LinkService.peerPid());
        m.put("link", link);
        if (p != null) {
            Map<String, Object> pl = new LinkedHashMap<>();
            pl.put("x", p.getX()); pl.put("y", p.getY()); pl.put("z", p.getZ());
            pl.put("health", p.getBodyDamage() != null ? p.getBodyDamage().getOverallBodyHealth() : null);
            pl.put("hunger", p.getStats() != null ? p.getStats().get(zombie.characters.CharacterStat.HUNGER) : null);
            pl.put("hasSquare", p.getCurrentSquare() != null);
            // PZ's animation state: anything but idle/walking/turning while Steve drives moves Viewpoint's camera
            pl.put("actionState", String.valueOf(p.getCurrentActionContextStateName()));
            pl.put("steveDrives", SteveControl.drives(p));
            zombie.iso.IsoGridSquare sq = p.getCurrentSquare();
            if (sq != null) {
                // Solid because of PZ itself (walls, furniture) or only because a Minecraft block stands in the tile
                // (then Steve may legitimately stand on top of it).
                pl.put("square", Map.of("solid", sq.isSolid(), "solidTrans", sq.isSolidTrans(),
                        "minecraftBlock", BlockBridge.has(sq.getX(), sq.getY(), sq.getZ())));
            }
            m.put("player", pl);
        }
        PlayerState s = new PlayerState();
        if (LinkService.pullInto(s)) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("x", s.x); st.put("y", s.y); st.put("z", s.z); st.put("yaw", s.yawDeg); st.put("pitch", s.pitchDeg);
            st.put("onGround", s.onGround()); st.put("flags", s.flags);
            m.put("steve", st);
        }
        m.put("nativeEntities", NativeEntities.stats());
        m.put("terrain", WorldExporter.stats());
        m.put("streaming",Streaming.stats());
        m.put("pzTrees",TreeBridge.stats());
        m.put("pzMining",MineBridge.stats());
        m.put("pzFurniture",FurnitureBridge.stats());
        m.put("pzCars",CarBridge.stats());
        m.put("pzSpawns",SpawnBridge.stats());
        m.put("pzSounds",SoundBridge.stats());
        m.put("pzTiles",TileBridge.stats());
        m.put("pzMenus",PzMenus.stats());
        m.put("fire",FireBridge.stats());
        m.put("handLight",NativeEntities.handLightStats());
        m.put("pzTileExport",TileExporter.stats());
        m.put("pzExplosions",ExplosionBridge.stats());
        m.put("pzGround",GroundBridge.stats());
        m.put("itemFall",ItemFall.stats());
        m.put("vehicleFloors",VehicleFloors.stats());
        m.put("pzItems",ItemExporter.stats());
        m.put("pzContainers",ContainerBridge.stats());
        m.put("pzMirror",MirrorBridge.stats());
        m.put("pzWeapons",Gameplay.weaponStats());
        m.put("pzHud",HudBridge.stats());
        m.put("pzGuns", GunBridge.stats());
        m.put("health", HealthLink.stats());
        m.put("steveVitals", SteveVitals.stats());
        m.put("zombies", Gameplay.lastActorCount);
        m.put("blocks", BlockBridge.size());
        m.put("overlay", Overlay.stats());
        m.put("nativeBlocks", NativeBlocks.stats());
        m.put("vehicles", VehicleBridge.count);
        var climate = zombie.iso.weather.ClimateManager.getInstance();
        if (climate != null) {
            var w = WeatherBridge.state(climate);
            Map<String, Object> weather = new LinkedHashMap<>();
            weather.put("precipitation", w.precipitation()); weather.put("snow", w.snow()); weather.put("thunder", w.thunder());
            weather.put("fog", w.fog()); weather.put("wind", w.wind()); weather.put("clouds", w.clouds());
            weather.put("lastCommand", WeatherBridge.lastCommand());
            m.put("weather", weather);
        }
        m.put("input", InputForwarder.status());
        m.put("overrideActive", InputOverride.active(System.currentTimeMillis()));
        return m;
    }

    /** Explicit local screen playtests, through the same path as the PZ window callbacks. Keys are GLFW codes. */
    private static Map<String, Object> gui(Map<String, Object> req) {
        int mods = ((Number) req.getOrDefault("mods", 0)).intValue();
        if (req.containsKey("key")) {
            int key = ((Number) req.get("key")).intValue();
            int action = ((Number) req.getOrDefault("action", 1)).intValue();
            boolean routed = GuiInput.key(key, action, mods);
            if (action == 1 && (key == 69 || key == 84 || key == 47)) GuiInput.character(Character.toLowerCase(key));
            return Map.of("ok", routed);
        }
        if (req.containsKey("button") && req.containsKey("x")) {
            return Map.of("ok", GuiInput.button(((Number)req.get("button")).intValue(),((Number)req.getOrDefault("action",1)).intValue(),mods,
                    ((Number)req.get("x")).doubleValue(),((Number)req.get("y")).doubleValue()));
        }
        if (!GuiInput.ownsInput()) throw new IllegalArgumentException("no Minecraft screen owns input");
        if (req.containsKey("text")) String.valueOf(req.get("text")).codePoints().forEach(GuiInput::character);
        if (req.containsKey("x")) {
            double x = ((Number) req.get("x")).doubleValue(), y = ((Number) req.get("y")).doubleValue();
            int type = req.containsKey("button") ? pzcraft.protocol.GuiEvent.BUTTON : pzcraft.protocol.GuiEvent.MOVE;
            int button = ((Number) req.getOrDefault("button", 0)).intValue();
            int action = ((Number) req.getOrDefault("action", 0)).intValue();
            GuiInput.emit(type, button, action, mods, x, y);
        }
        if (req.containsKey("scroll")) GuiInput.emit(pzcraft.protocol.GuiEvent.SCROLL, 0, 0, mods, 0,
                ((Number) req.get("scroll")).doubleValue());
        return Map.of("ok", true);
    }

    private static Map<String, Object> input(Map<String, Object> req) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        if (req.get("release") == Boolean.TRUE) InputOverride.releaseAll();
        if (req.get("look") instanceof Map<?, ?> lk) {
            boolean rel = !Boolean.FALSE.equals(lk.get("relative"));
            Float yaw = lk.get("yaw") instanceof Number n ? n.floatValue() : null;
            Float pitch = lk.get("pitch") instanceof Number n ? n.floatValue() : null;
            InputOverride.look(yaw, pitch, rel);
        }
        if (req.get("hotbar") instanceof Number n) InputOverride.selectHotbar(n.intValue() - 1);
        long ms = req.get("ms") instanceof Number n ? n.longValue() : 200;
        if (req.get("hold") instanceof List<?> hold && !hold.isEmpty()) {
            InputOverride.hold(InputOverride.maskOf(hold), ms);
        }
        if (req.get("wait") == Boolean.TRUE) Thread.sleep(Math.min(ms, 30_000) + 100);
        out.put("ok", true);
        return out;
    }

    private static Map<String, Object> lua(String code) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        var fn = se.krka.kahlua.luaj.compiler.LuaCompiler.loadstring(code, "pzcraft-ctl", zombie.Lua.LuaManager.env);
        Object[] res = zombie.Lua.LuaManager.caller.pcall(zombie.Lua.LuaManager.thread, fn);
        boolean ok = res != null && res.length > 0 && Boolean.TRUE.equals(res[0]);
        List<String> vals = new ArrayList<>();
        if (res != null) for (int i = 1; i < res.length; i++) vals.add(String.valueOf(res[i]));
        out.put("ok", ok);
        out.put("results", vals);
        return out;
    }

    private static Map<String, Object> menu(String action) throws Exception {
        return switch (action) {
            case "continue" -> lua("MainScreen.continueLatestSave(MainScreen.latestSaveGameMode, MainScreen.latestSaveWorld)");
            case "start" -> {
                // "Click to start" after loading: what the click does, without touching the real mouse or window focus
                Object st = zombie.GameWindow.states.current;
                if (!(st instanceof zombie.gameStates.GameLoadingState)) {
                    yield Map.of("ok", false, "result", "not on the loading screen (" + (st == null ? "?" : st.getClass().getSimpleName()) + ")");
                }
                java.lang.reflect.Field f = zombie.gameStates.GameLoadingState.class.getDeclaredField("forceDone");
                f.setAccessible(true);
                f.setBoolean(st, true);
                yield Map.of("ok", true, "result", "the loading screen continues as soon as loading is done");
            }
            case "view3d" -> Map.of("ok", ViewpointBridge.enableView(), "view3d", ViewpointBridge.viewEnabled());
            default -> throw new IllegalArgumentException("unknown menu action '" + action + "' (continue, start, view3d)");
        };
    }
}
