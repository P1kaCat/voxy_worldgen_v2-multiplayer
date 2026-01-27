package com.ethan.voxyworldgenv2.integration;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import net.minecraft.world.level.chunk.LevelChunk;

public final class VoxyIntegration {
    private static boolean voxyAvailable = false;
    private static boolean checkedAvailability = false;
    
    private VoxyIntegration() {}
    
    public static void ingestChunk(LevelChunk chunk) {
        if (!isVoxyAvailable()) return;
        
        try {
            Class<?> ingestServiceClass = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
            Object serviceInstance = null;
            try {
                var instanceField = ingestServiceClass.getDeclaredField("INSTANCE");
                serviceInstance = instanceField.get(null);
            } catch (Exception ignored) {}

            String[] commonMethods = {"ingestChunk", "tryAutoIngestChunk", "enqueueIngest", "ingest"};
            java.lang.reflect.Method targetMethod = null;
            for (String methodName : commonMethods) {
                try {
                    targetMethod = ingestServiceClass.getMethod(methodName, LevelChunk.class);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }

            if (targetMethod != null) {
                targetMethod.invoke(serviceInstance, chunk);
            }
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.warn("voxy chunk ingestion failed: {}", e.toString());
            voxyAvailable = false;
        }
    }
    
    public static boolean isVoxyAvailable() {
        if (!checkedAvailability) {
            checkedAvailability = true;
            try {
                Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
                voxyAvailable = true;
                VoxyWorldGenV2.LOGGER.info("voxy integration enabled");
            } catch (ClassNotFoundException e) {
                voxyAvailable = false;
                VoxyWorldGenV2.LOGGER.info("voxy not found");
            }
        }
        return voxyAvailable;
    }
}
