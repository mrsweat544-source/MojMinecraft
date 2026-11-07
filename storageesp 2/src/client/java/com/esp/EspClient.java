package com.esp;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class EspClient implements ClientModInitializer {
    public static final String ID = "storageesp";

    @Override
    public void onInitializeClient() {
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
        KeyMapping freecamKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + ID + ".freecam", InputConstants.Type.KEYSYM, InputConstants.KEY_F6, cat));
        KeyMapping espKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + ID + ".esp", InputConstants.Type.KEYSYM, InputConstants.KEY_F7, cat));

        // Runs before vanilla handles input, so we can swallow attack/use clicks while in freecam.
        ClientTickEvents.START_CLIENT_TICK.register(Freecam::blockInteractions);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (freecamKey.consumeClick()) Freecam.toggle(mc);
            while (espKey.consumeClick()) {
                StorageEsp.enabled = !StorageEsp.enabled;
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal("Storage ESP: " + (StorageEsp.enabled ? "ON" : "OFF")));
                }
            }
            Freecam.tick(mc);
        });

        LevelRenderEvents.END_EXTRACTION.register(StorageEsp::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(StorageEsp::renderAndDraw);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> StorageEsp.close());
    }
}
