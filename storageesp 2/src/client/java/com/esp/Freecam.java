package com.esp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Freecam: a client-only camera entity that is never added to the world.
 * The real player is frozen (empty input) and the mouse keeps turning the player's head,
 * which the camera copies. Nothing is sent to the server from the camera.
 */
public final class Freecam {
    private static boolean active;
    private static Cam cam;
    private static ClientInput savedInput;

    /** Camera entity whose view angles follow the real player's, every frame (no 20 Hz stutter). */
    static final class Cam extends RemotePlayer {
        Cam(ClientLevel level, Minecraft mc) {
            super(level, mc.player.getGameProfile());
        }
        @Override public float getViewYRot(float partial) { return Minecraft.getInstance().player.getYRot(); }
        @Override public float getViewXRot(float partial) { return Minecraft.getInstance().player.getXRot(); }
        @Override public boolean isSpectator() { return true; }
    }

    public static boolean isActive() { return active; }

    public static void toggle(Minecraft mc) {
        if (active) disable(mc); else enable(mc);
    }

    private static void enable(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        cam = new Cam(mc.level, mc);
        cam.noPhysics = true;
        Vec3 eye = mc.player.getEyePosition();
        cam.setPos(eye.x, eye.y - cam.getEyeHeight(), eye.z);
        cam.setYRot(mc.player.getYRot());
        cam.setXRot(mc.player.getXRot());
        cam.setOldPosAndRot();

        savedInput = mc.player.input;
        mc.player.input = new ClientInput(); // empty input: the real player stands still
        mc.setCameraEntity(cam);
        active = true;
        mc.player.sendSystemMessage(Component.literal("Freecam: ON (F6 to exit)"));
    }

    private static void disable(Minecraft mc) {
        active = false;
        cam = null;
        if (mc.player != null) {
            if (savedInput != null) mc.player.input = savedInput;
            mc.setCameraEntity(mc.player);
            mc.player.sendSystemMessage(Component.literal("Freecam: OFF"));
        }
        savedInput = null;
    }

    /** Called every client tick. */
    static void tick(Minecraft mc) {
        if (!active) return;
        if (mc.player == null || mc.level == null) { // disconnected
            active = false; cam = null; savedInput = null;
            return;
        }
        Options o = mc.options;
        Vec3 look = mc.player.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0, look.x).normalize();

        double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double s = (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0);
        double u = (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0);

        Vec3 move = look.scale(f).add(right.scale(s)).add(0, u, 0);
        if (move.lengthSqr() > 1.0E-6) {
            move = move.normalize().scale(o.keySprint.isDown() ? 1.0 : 0.5);
        }
        cam.setOldPosAndRot();
        cam.setPos(cam.position().add(move));
        cam.setYRot(mc.player.getYRot());
        cam.setXRot(mc.player.getXRot());
    }

    /** Stop the camera's crosshair from breaking/placing/using things on the server. */
    static void blockInteractions(Minecraft mc) {
        if (!active) return;
        Options o = mc.options;
        o.keyAttack.setDown(false);
        o.keyUse.setDown(false);
        o.keyPickItem.setDown(false);
        while (o.keyAttack.consumeClick()) { }
        while (o.keyUse.consumeClick()) { }
        while (o.keyPickItem.consumeClick()) { }
    }
}
