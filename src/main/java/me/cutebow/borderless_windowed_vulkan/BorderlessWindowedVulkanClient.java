package me.cutebow.borderless_windowed_vulkan;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class BorderlessWindowedVulkanClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BorderlessWindowedVulkanConfig.HANDLER.load();

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> BorderlessWindowedVulkanWindowController.tick(client));
        ClientTickEvents.END_CLIENT_TICK.register(BorderlessWindowedVulkanWindowController::tick);
    }
}
