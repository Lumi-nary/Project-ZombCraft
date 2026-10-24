package pzcraft.mc;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
//#if MC_1_20
//#else
import net.minecraft.client.input.CharacterEvent;
//#endif
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import pzcraft.protocol.GuiEvent;
import pzcraft.protocol.Wire;

/** Dispatches the visible PZ window's events through Minecraft's native screen handlers on the client thread. */
public final class GuiBridge {
    private static final ConcurrentLinkedQueue<GuiEvent> events = new ConcurrentLinkedQueue<>();
    private static final Gson gson = new Gson();
    private static int handled;
    private static long peer, lastState;
    private static Screen previous;
    private static long loadingHint;
    private GuiBridge() {}

    //#if MC_1_20
    //$ /** 1.20.1 keeps MouseHandler's GLFW callbacks private and obfuscated: call them through an invoker mixin. */
    //$ private static void mouse(Minecraft mc, String name, Object... args) {
    //$     var h = (pzcraft.mc.mixin.MouseHandlerInvoker) mc.mouseHandler;
    //$     long window = (Long) args[0];
    //$     switch (name) {
    //$         case "onScroll" -> h.pzcraft$onScroll(window, ((Number) args[1]).doubleValue(), ((Number) args[2]).doubleValue());
    //$         case "onMove" -> h.pzcraft$onMove(window, ((Number) args[1]).doubleValue(), ((Number) args[2]).doubleValue());
    //$         case "onButton" -> {
    //$             var info = (pzcraft.mc.compat.MouseButtonInfo) args[1];
    //$             h.pzcraft$onPress(window, info.button(), (Integer) args[2], info.modifiers());
    //$         }
    //$         default -> PzCraftMain.LOG.warn("mouse event {} not delivered", name);
    //$     }
    //$ }
    //#elif MC_26_1
    //$ private static final java.util.Map<String, java.lang.reflect.Method> mouseMethods = new java.util.HashMap<>();
    //$
    //$ /** 26.1.2 keeps MouseHandler's GLFW callbacks private: call them by name (onScroll, onMove, onButton). */
    //$ private static void mouse(Minecraft mc, String name, Object... args) {
    //$     try {
    //$         var method = mouseMethods.get(name);
    //$         if (method == null) {
    //$             for (var m : net.minecraft.client.MouseHandler.class.getDeclaredMethods())
    //$                 if (m.getName().equals(name) && m.getParameterCount() == args.length) method = m;
    //$             if (method == null) throw new NoSuchMethodException("MouseHandler." + name);
    //$             method.setAccessible(true);
    //$             mouseMethods.put(name, method);
    //$         }
    //$         method.invoke(mc.mouseHandler, args);
    //$     } catch (ReflectiveOperationException e) {
    //$         PzCraftMain.LOG.warn("mouse event {} not delivered: {}", name, e.toString());
    //$     }
    //$ }
    //#endif

    static void enqueue(GuiEvent event) { if (events.size() < 4096) events.add(event); }

    public static void beforeFrame(Minecraft mc) {
        if (!Session.pzConnected || mc.player == null) { events.clear(); return; }
        BetterCombatCompat.tick();
        BetterCombatCompat.applyArms();
        long pid = Session.link.peerPidOrZero();
        if (pid != peer) { peer = pid; handled = 0; }
        GuiEvent e;
        while ((e = events.poll()) != null) {
            //#if MC_26_1
            //$ Screen screen = mc.screen;
            //#else
            Screen screen = mc.gui.screen();
            //#endif
            ModKeys.track(e.type(), e.value(), e.action(), e.modifiers(), screen != null);
            if(e.type()==GuiEvent.WINDOW_MODE) {
                WindowMode.set(mc,e.value()!=0);
            } else if (e.type() == GuiEvent.CAMERA_MODE && e.value() >= 0 && e.value() <= 2) {
                camera(mc, net.minecraft.client.CameraType.values()[e.value()]);
            } else if (e.type() == GuiEvent.KEY) {
                KeyEvent key = key(e.value(), e.modifiers());
                if (screen == null && e.action() == 1) {
                    //#if MC_1_20
                    //$ if (mc.options.keyTogglePerspective.matches(key.key(), key.scancode())) {
                    //#else
                    if (mc.options.keyTogglePerspective.matches(key)) {
                    //#endif
                        camera(mc, mc.options.getCameraType().cycle());
                    } else if (e.value() == 82 && PzGuns.reloadKey(mc)) { }
                    else if (e.value() == 73 && PzGuns.inspectKey(mc)) { }
                    else if (e.value() == 66 && PzGuns.fireModeKey(mc)) { }
                    else if (e.value() == 88 && PzGuns.rackKey(mc)) { }
                    //#if MC_26_1
                    //$ else if (e.value() == 69) mc.setScreen(new InventoryScreen(mc.player));
                    //#else
                    else if (e.value() == 69) mc.gui.setScreen(new InventoryScreen(mc.player));
                    //#endif
                    //#if MC_1_20
                    //$ else if (e.value() == 84) mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen(""));
                    //#elif MC_26_1
                    //$ else if (e.value() == 84) mc.openChatScreen(ChatComponent.ChatMethod.MESSAGE);
                    //#else
                    else if (e.value() == 84) mc.gui.openChatScreen(ChatComponent.ChatMethod.MESSAGE);
                    //#endif
                    //#if MC_1_20
                    //$ else if (e.value() == 47) mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("/"));
                    //#elif MC_26_1
                    //$ else if (e.value() == 47) mc.openChatScreen(ChatComponent.ChatMethod.COMMAND);
                    //#else
                    else if (e.value() == 47) mc.gui.openChatScreen(ChatComponent.ChatMethod.COMMAND);
                    //#endif
                    else ModKeys.key(mc, e.value(), e.action());
                } else if (screen == null) {
                    ModKeys.key(mc, e.value(), e.action());   // another mod's binding: its release and repeats
                } else {
                    mc.setLastInputType(InputType.KEYBOARD_TAB);
                    //#if MC_1_20
                    //$ if (e.action() == 0) screen.keyReleased(key.key(), key.scancode(), key.modifiers());
                    //$ else { screen.afterKeyboardAction(); screen.keyPressed(key.key(), key.scancode(), key.modifiers()); }
                    //#else
                    if (e.action() == 0) screen.keyReleased(key);
                    else { screen.afterKeyboardAction(); screen.keyPressed(key); }
                    //#endif
                }
            } else if (screen != null || e.type()==GuiEvent.BUTTON && e.action()==0) {
                long window = mc.getWindow().handle();
                //#if MC_1_20
                //$ // 1.20.1 takes UTF-16 chars: a code point outside the BMP arrives as its two surrogates, as KeyboardHandler does
                //$ if (e.type() == GuiEvent.CHARACTER) { for (char c : Character.toChars(e.value())) screen.charTyped(c, 0); }
                //#else
                if (e.type() == GuiEvent.CHARACTER) screen.charTyped(new CharacterEvent(e.value()));
                //#endif
                //#if MC_26_1
                //$ else if (e.type() == GuiEvent.SCROLL) mouse(mc, "onScroll", window, e.x(), e.y());
                //#else
                else if (e.type() == GuiEvent.SCROLL) mc.mouseHandler.onScroll(window, e.x(), e.y());
                //#endif
                else {
                    // Actual handler coordinates also drive tooltips, hover and the inventory mannequin.
                    double x = e.x() * mc.getWindow().getScreenWidth(), y = e.y() * mc.getWindow().getScreenHeight();
                    //#if MC_26_1
                    //$ if(screen!=null)mouse(mc, "onMove", window, x, y);
                    //#else
                    if(screen!=null)mc.mouseHandler.onMove(window, x, y, 0, 0);
                    //#endif
                    if (e.type() == GuiEvent.BUTTON) {
                        int button = switch (e.value()) { case 0 -> 1; case 1 -> 3; case 2 -> 2; default -> e.value() + 1; };
                        //#if MC_26_1
                        //$ // 26.1.2 numbers buttons as GLFW does, like PZ (0 left, 1 right): the SDL numbers above made every left click a right click.
                        //$ mouse(mc, "onButton", window, new MouseButtonInfo(e.value(), modifiers(e.modifiers())), e.action());
                        //#else
                        mc.mouseHandler.onButton(window, new MouseButtonInfo(button, modifiers(e.modifiers())), e.action());
                        //#endif
                    }
                    //#if MC_1_20
                    //$ // 1.20.1 delivers a move to the screen inside onMove
                    //#else
                    mc.mouseHandler.handleAccumulatedMovement();
                    //#endif
                }
            }
            handled = e.sequence();
        }
        publish(mc);
        if (TerrainCoverage.loading() && System.nanoTime()-loadingHint>1_000_000_000L) {
            loadingHint=System.nanoTime();
            //#if MC_26_1
            //$ mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal("PZ is loading terrain…"),false);
            //#else
            mc.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal("PZ is loading terrain…"),false);
            //#endif
        }
    }

    private static void camera(Minecraft mc, net.minecraft.client.CameraType type) {
        var previous = mc.options.getCameraType();
        mc.options.setCameraType(type);
        if (previous.isFirstPerson() != type.isFirstPerson())
            mc.gameRenderer.checkEntityPostEffect(type.isFirstPerson() ? mc.getCameraEntity() : null);
    }

    private static void publish(Minecraft mc) {
        long now = System.nanoTime();
        if (mc.player == null) return;   // the world is closing (PZ quit): nothing to publish, and not a crash
        //#if MC_26_1
        //$ Screen screen = mc.screen;
        //#else
        Screen screen = mc.gui.screen();
        //#endif
        if (screen == previous && now - lastState < 100_000_000L) return;
        previous = screen; lastState = now;
        var state = new LinkedHashMap<String, Object>();
        state.put("handled", handled);
        state.put("screen", screen == null ? "" : screen.getClass().getSimpleName());
        state.put("width", mc.getWindow().getGuiScaledWidth());
        state.put("height", mc.getWindow().getGuiScaledHeight());
        if (screen != null && screen.getFocused() instanceof EditBox edit) state.put("text", edit.getValue());
        state.put("carried", mc.player.containerMenu.getCarried().toString());
        state.put("perspectiveKey", glfwKey(mc.options.keyTogglePerspective.saveString()));
        state.put("modKeys", ModKeys.keys(mc));
        state.put("cameraMode", mc.options.getCameraType().ordinal());
        state.put("firstPersonHand", mc.options.getCameraType().isFirstPerson());
        state.put("pzCars", PzCars.stats());
        state.put("pzSounds", McSounds.stats());
        var slots = new ArrayList<String>();
        for (int i = 0; i < 36; i++) slots.add(PzItems.describe(mc.player.getInventory().getItem(i)));
        state.put("inventory", slots);
        state.put("selectedSlot", mc.player.getInventory().getSelectedSlot());
        var heldStack = mc.player.getMainHandItem();
        var held = new java.util.LinkedHashMap<String, Object>();
        held.put("item", PzItems.describe(heldStack));
        var heldState = PzItems.stateOf(heldStack);
        held.put("pzId", heldState.has("i") ? heldState.get("i").getAsInt() : -1);   // PZ's menu for this item (G on nothing, Shift+G)
        held.put("attackDamage", mc.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));
        held.put("attackSpeed", mc.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED));
        // the client does not get the attack damage attribute (only the server's player has the real value): read it there
        var singleplayer = mc.getSingleplayerServer();
        if (singleplayer != null && !singleplayer.getPlayerList().getPlayers().isEmpty()) {
            held.put("serverAttackDamage", singleplayer.getPlayerList().getPlayers().getFirst().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));
        }
        held.put("preset", PzWeapons.presetOf(heldStack));
        held.put("treeSpeed", PzBlocks.TREE == null ? 0 : heldStack.getDestroySpeed(PzBlocks.TREE.defaultBlockState()));
        held.put("durability", heldStack.isDamageableItem() ? heldStack.getMaxDamage() - heldStack.getDamageValue() + "/" + heldStack.getMaxDamage() : "");
        state.put("held", held);
        state.put("combat",ActorProxies.combatStats());
        state.put("gun", PzGuns.state());
        state.put("gunFeel", GunFeel.stats());
        state.put("server",ActorProxies.worldStats);
        state.put("terrain",TerrainCoverage.stats());
        state.put("materials",MaterialField.stats());
        state.put("trees",TreeBridge.stats());
        state.put("explosions",ExplosionBridge.stats());
        state.put("fire",PzFire.stats());
        state.put("ground",GroundBridge.stats());
        state.put("pzItemCatalog",PzItemCatalog.size());
        state.put("pzContainers",ContainerBridge.stats());
        state.put("mirror",MirrorBridge.stats());
        state.put("food",mc.player.getFoodData().getFoodLevel());
        state.put("saturation",mc.player.getFoodData().getSaturationLevel());
        state.put("armor",mc.player.getArmorValue());
        state.put("worn",java.util.List.of(PzItems.describe(mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)),
                PzItems.describe(mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)),
                PzItems.describe(mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS)),
                PzItems.describe(mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET))));
        if(mc.player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu chest) {
            var shown=new ArrayList<String>();
            for(int i=0;i<chest.getRowCount()*9;i++)shown.add(PzItems.describe(chest.getSlot(i).getItem()));
            state.put("menuRows",chest.getRowCount());
            state.put("menuSlots",shown);
        }
        var velocity=mc.player.getDeltaMovement();
        state.put("velocity",java.util.List.of(velocity.x*20,velocity.y*20,velocity.z*20));
        state.put("fallFlying",mc.player.isFallFlying());state.put("flying",mc.player.getAbilities().flying);
        //#if MC_1_20
        //$ state.put("videoEntities",0); // no render states on 1.20.1
        //#elif MC_26_1
        //$ state.put("videoEntities",mc.gameRenderer.getGameRenderState().levelRenderState.entityRenderStates.size());
        //#else
        state.put("videoEntities",mc.gameRenderer.gameRenderState().levelRenderState.entityRenderStates.size());
        //#endif
        state.put("worldVideo",!Session.nativeWorld);
        state.put("hiddenWindow",WindowMode.hidden);
        Session.send(Wire.MSG_GUI_STATE, gson.toJson(state).getBytes(StandardCharsets.UTF_8));
    }

    private static int modifiers(int glfw) {
        //#if MC_1_20
        //$ return glfw & 15; // 1.20.1 takes GLFW's modifier bits as they are, like PZ sends them
        //#else
        return ((glfw & 1) != 0 ? InputConstants.MOD_SHIFT : 0)
                | ((glfw & 2) != 0 ? InputConstants.MOD_CONTROL : 0)
                | ((glfw & 4) != 0 ? InputConstants.MOD_ALT : 0)
                | ((glfw & 8) != 0 ? InputConstants.MOD_SUPER : 0);
        //#endif
    }

    /** Publish the rebound perspective key to PZ, whose GLFW callback owns the visible window. */
    private static int glfwKey(String binding) {
        if (binding.startsWith("key.keyboard.f")) {
            try { int n = Integer.parseInt(binding.substring(14)); if (n >= 1 && n <= 12) return 289 + n; }
            catch (NumberFormatException ignored) {}
        }
        if (binding.startsWith("key.keyboard.") && binding.length() == 14) return Character.toUpperCase(binding.charAt(13));
        return switch (binding) {
            case "key.keyboard.tab" -> 258; case "key.keyboard.grave.accent" -> 96;
            case "key.keyboard.space" -> 32; case "key.keyboard.insert" -> 260; case "key.keyboard.delete" -> 261;
            case "key.keyboard.home" -> 268; case "key.keyboard.end" -> 269;
            case "key.keyboard.page.up" -> 266; case "key.keyboard.page.down" -> 267;
            default -> -1;
        };
    }

    /** PZ uses GLFW; Minecraft 26.3 uses SDL physical scancodes and logical shortcut keycodes. */
    private static KeyEvent key(int glfw, int mods) {
        //#if MC_26_1
        //$ // 26.1.2 is GLFW like PZ: KeyEvent(GLFW key, platform scancode, mods). Without this Escape, Enter and Backspace never reached a screen.
        //$ if (true) return new KeyEvent(glfw, org.lwjgl.glfw.GLFW.glfwGetKeyScancode(glfw), modifiers(mods));
        //#endif
        int scan;
        if (glfw >= 65 && glfw <= 90) scan = 4 + glfw - 65;
        else if (glfw >= 49 && glfw <= 57) scan = 30 + glfw - 49;
        else if (glfw >= 290 && glfw <= 301) scan = 58 + glfw - 290;
        else scan = switch (glfw) {
            case 48 -> 39; case 32 -> 44; case 39 -> 52; case 44 -> 54; case 45 -> 45;
            case 46 -> 55; case 47 -> 56; case 59 -> 51; case 61 -> 46;
            case 91 -> 47; case 92 -> 49; case 93 -> 48; case 96 -> 53;
            case 256 -> 41; case 257 -> 40; case 258 -> 43; case 259 -> 42;
            case 260 -> 73; case 261 -> 76; case 262 -> 79; case 263 -> 80;
            case 264 -> 81; case 265 -> 82; case 266 -> 75; case 267 -> 78;
            case 268 -> 74; case 269 -> 77; case 280 -> 57;
            case 340 -> 225; case 341 -> 224; case 342 -> 226; case 343 -> 227;
            case 344 -> 229; case 345 -> 228; case 346 -> 230; case 347 -> 231;
            case 335 -> 88; default -> 0;
        };
        int logical = glfw >= 65 && glfw <= 90 ? glfw + 32 : glfw < 256 ? glfw
                : scan == 40 ? 13 : scan == 41 ? 27 : scan == 42 ? 8 : scan == 43 ? 9 : scan | 0x40000000;
        return new KeyEvent(scan, logical, modifiers(mods));
    }
}
