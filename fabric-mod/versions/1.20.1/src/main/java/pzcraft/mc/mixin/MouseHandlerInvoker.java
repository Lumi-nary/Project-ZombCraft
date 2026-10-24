package pzcraft.mc.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 1.20.1 only: MouseHandler's GLFW callbacks are private. 26.1.2 calls them by reflection (GuiBridge.mouse), which
 * cannot work here: in an obfuscated game the runtime names are intermediary ones.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerInvoker {
    @Invoker("onPress") void pzcraft$onPress(long window, int button, int action, int mods);
    @Invoker("onMove") void pzcraft$onMove(long window, double x, double y);
    @Invoker("onScroll") void pzcraft$onScroll(long window, double x, double y);
}
