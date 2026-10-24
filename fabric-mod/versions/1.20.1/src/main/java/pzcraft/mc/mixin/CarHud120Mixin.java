package pzcraft.mc.mixin;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.PzCars;
import pzcraft.mc.Session;

/** Lift bottom HUD elements over the native vehicle dashboard, preserving the crosshair. */
@Mixin(Gui.class)
public abstract class CarHud120Mixin {
    @Inject(method={"renderPlayerHealth","renderVehicleHealth","renderExperienceBar","renderSelectedItemName"},at=@At("HEAD"))
    private void lift(GuiGraphics graphics,CallbackInfo ci) { graphics.pose().pushPose(); if(Session.pzConnected && PzCars.seat.inCar())graphics.pose().translate(0,-Math.round(PzCars.seat.dashboard()*graphics.guiHeight()),0); }
    @Inject(method={"renderPlayerHealth","renderVehicleHealth","renderExperienceBar","renderSelectedItemName"},at=@At("RETURN"))
    private void restore(GuiGraphics graphics,CallbackInfo ci) { graphics.pose().popPose(); }
    @Inject(method="renderHotbar",at=@At("HEAD"))
    private void hotbarLift(float partial,GuiGraphics graphics,CallbackInfo ci) { lift(graphics,ci); }
    @Inject(method="renderHotbar",at=@At("RETURN"))
    private void hotbarRestore(float partial,GuiGraphics graphics,CallbackInfo ci) { restore(graphics,ci); }
}
