package com.ethan.voxyworldgenv2;

import com.ethan.voxyworldgenv2.client.DebugRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

@Environment(EnvType.CLIENT)
public class VoxyWorldGenV2Client implements ClientModInitializer {
    
    @Override
    public void onInitializeClient() {
        VoxyWorldGenV2.LOGGER.info("initializing voxy world gen v2 client");
        
        // debug hud renderer
        HudRenderCallback.EVENT.register(DebugRenderer::render);
    }
}
