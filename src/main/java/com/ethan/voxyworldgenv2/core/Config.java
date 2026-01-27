package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Config {
    
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("voxyworldgenv2.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    public static ConfigData DATA = new ConfigData();
    
    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            // auto-configure on fresh install for general public usage
            int cores = Runtime.getRuntime().availableProcessors();
            long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024); // mb
            
            // scaling logic:
            // for active tasks, we want to utilize available threads but leave room for c2me and server.
            // safely using 50% of cores is a good baseline imo for background tasks.
            DATA.maxActiveTasks = Math.max(1, Math.min(16, cores / 2));
            
            // for radius, memory is the main constraint. 
            // 4gb+ can handle 64 chunks easily. 8gb+ can handle 128.
            if (maxMemory > 8000 && cores >= 12) {
                DATA.generationRadius = 128; // extreme
            } else if (maxMemory > 4000 && cores >= 6) {
                DATA.generationRadius = 64; // high
            } else {
                DATA.generationRadius = 32; // standard
            }
            
            save();
            return;
        }
        
        try (var reader = Files.newBufferedReader(CONFIG_PATH)) {
            DATA = GSON.fromJson(reader, ConfigData.class);
        } catch (IOException e) {
            VoxyWorldGenV2.LOGGER.error("failed to load config", e);
        }
    }
    
    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (var writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(DATA, writer);
            }
        } catch (IOException e) {
            VoxyWorldGenV2.LOGGER.error("failed to save config", e);
        }
    }
    
    public static class ConfigData {
        public int generationRadius = 32;
        public int queueUpdateInterval = 20;
        public int maxQueueSize = 20000;
        public int maxActiveTasks = 4;
    }
}
