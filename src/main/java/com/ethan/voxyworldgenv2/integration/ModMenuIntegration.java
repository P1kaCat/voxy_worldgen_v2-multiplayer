package com.ethan.voxyworldgenv2.integration;

import com.ethan.voxyworldgenv2.core.Config;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.voxyworldgenv2.title"));
            
            ConfigEntryBuilder entryBuilder = builder.entryBuilder();
            ConfigCategory general = builder.getOrCreateCategory(Component.translatable("config.voxyworldgenv2.category.general"));
            
            general.addEntry(entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.radius"), Config.DATA.generationRadius, 1, 512)
                .setDefaultValue(128)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.radius.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.generationRadius = newValue)
                .build());
            
            // tps option removed as it is now hardcoded for stability
                
            general.addEntry(entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.update_interval"), Config.DATA.queueUpdateInterval, 1, 200)
                .setDefaultValue(20)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.update_interval.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.queueUpdateInterval = newValue)
                .build());
                
            general.addEntry(entryBuilder.startIntField(Component.translatable("config.voxyworldgenv2.option.max_queue"), Config.DATA.maxQueueSize)
                .setDefaultValue(20000)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.max_queue.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.maxQueueSize = newValue)
                .build());
                
            general.addEntry(entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.max_active"), Config.DATA.maxActiveTasks, 1, 128)
                .setDefaultValue(16)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.max_active.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.maxActiveTasks = newValue)
                .build());
            
            builder.setSavingRunnable(() -> {
                Config.save();
                com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().scheduleConfigReload();
            });
            
            return builder.build();
        };
    }
}
