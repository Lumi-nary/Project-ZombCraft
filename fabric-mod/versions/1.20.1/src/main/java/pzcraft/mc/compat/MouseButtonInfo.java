package pzcraft.mc.compat;

/** 1.20.1 stand-in for 26.x's net.minecraft.client.input.MouseButtonInfo (renames.txt); GLFW button numbers. */
public record MouseButtonInfo(int button, int modifiers) {}
