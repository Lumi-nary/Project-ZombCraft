package pzcraft.mc;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import pzcraft.protocol.McStatus;

/**
 * Minecraft's start as PZ's menu shows it (Wire.MSG_MC_STATUS): still loading (with the progress of Minecraft's own
 * loading screen), at the title screen, or with a world open. PZ draws its own Mojang-style loading screen from it, with
 * the Mojang Studios logo of the player's own Minecraft, copied out of its jar here (nothing of Mojang's is shipped).
 */
final class McStart {
    static final Path UI = Path.of(System.getProperty("user.home"), ".pzcraft", "ui");
    private static final String LOGO = "/assets/minecraft/textures/gui/title/mojangstudios.png";

    private McStart() {}

    /** Link thread. Reads Minecraft's screen fields without a lock: a status a frame old is fine for a loading screen. */
    static McStatus status() {
        Minecraft mc = Minecraft.getInstance();
        String user = "";
        try { user = mc.getUser().getName(); } catch (RuntimeException e) { /* not set up yet */ }
        //#if MC_26_1
        //$ if (mc.gui != null && mc.getOverlay() instanceof LoadingOverlay overlay)
        //#else
        if (mc.gui != null && mc.gui.overlay() instanceof LoadingOverlay overlay)
        //#endif
            return new McStatus(McStatus.LOADING, ((pzcraft.mc.mixin.LoadingOverlayAccessor) overlay).pzcraft$progress(), user);
        if (mc.level != null) return new McStatus(McStatus.WORLD, 1f, user);
        //#if MC_26_1
        //$ if (mc.gui == null || mc.screen == null) return new McStatus(McStatus.LOADING, 0f, user);    // before the loading screen has come up
        //#else
        if (mc.gui == null || mc.gui.screen() == null) return new McStatus(McStatus.LOADING, 0f, user);    // before the loading screen has come up
        //#endif
        return new McStatus(McStatus.TITLE, 1f, user);
    }

    /** At start: the logo PZ's loading screen shows, from the Minecraft jar this game runs (written when it differs). */
    static void copyLogo() {
        try (InputStream in = Minecraft.class.getResourceAsStream(LOGO)) {
            if (in == null) { PzCraftMain.LOG.warn("Mojang Studios logo not found in the Minecraft jar ({})", LOGO); return; }
            byte[] bytes = in.readAllBytes();
            Path target = UI.resolve("mojangstudios.png");
            if (Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), bytes)) return;
            Files.createDirectories(UI);
            Path tmp = UI.resolve("mojangstudios.png.tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            PzCraftMain.LOG.warn("could not copy the Mojang Studios logo for PZ's loading screen", e);
        }
    }
}
