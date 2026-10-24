package pzcraft.mc.compat;

/** 1.20.1 stand-in for 26.x's net.minecraft.client.input.KeyEvent (renames.txt): 1.20.1 screens take these as ints. */
public record KeyEvent(int key, int scancode, int modifiers) {}
