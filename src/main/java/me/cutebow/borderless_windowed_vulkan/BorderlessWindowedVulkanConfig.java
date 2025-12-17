package me.cutebow.borderless_windowed_vulkan;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.nio.file.Path;

public class BorderlessWindowedVulkanConfig {
    public static final String MOD_ID = "borderless_windowed_vulkan";

    public static final ConfigClassHandler<BorderlessWindowedVulkanConfig> HANDLER = ConfigClassHandler.createBuilder(BorderlessWindowedVulkanConfig.class)
            .id(Identifier.of(MOD_ID, "config"))
            .serializer(config -> {
                Path path = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
                return GsonConfigSerializerBuilder.create(config)
                        .setPath(path)
                        .build();
            })
            .build();

    @SerialEntry
    public boolean enabled = true;

    public static BorderlessWindowedVulkanConfig get() {
        return HANDLER.instance();
    }

    public static Screen createScreen(Screen parent) {
        BorderlessWindowedVulkanConfig defaults = new BorderlessWindowedVulkanConfig();
        BorderlessWindowedVulkanConfig live = get();

        Option<Boolean> enabled = Option.<Boolean>createBuilder()
                .name(Text.literal("Enabled"))
                .description(OptionDescription.of(Text.literal("Restart required")))
                .binding(defaults.enabled, () -> live.enabled, v -> live.enabled = v)
                .controller(TickBoxControllerBuilder::create)
                .build();

        ConfigCategory general = ConfigCategory.createBuilder()
                .name(Text.literal("General"))
                .group(OptionGroup.createBuilder()
                        .name(Text.literal("Settings"))
                        .option(enabled)
                        .build())
                .build();

        return YetAnotherConfigLib.createBuilder()
                .title(Text.literal("Borderless Windowed Vulkan"))
                .category(general)
                .save(HANDLER::save)
                .build()
                .generateScreen(parent);
    }
}
