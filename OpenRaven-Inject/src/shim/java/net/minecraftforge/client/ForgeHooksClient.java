package net.minecraftforge.client;

import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.resources.model.IBakedModel;

/**
 * The one client-hooks call the client makes. Forge runs the model through the
 * registered transforms; with no mods registered the model is handed back
 * unchanged, which is what vanilla does.
 */
public final class ForgeHooksClient {

    private ForgeHooksClient() {
    }

    public static IBakedModel handleCameraTransforms(IBakedModel model,
                                                     ItemCameraTransforms.TransformType type) {
        return model;
    }
}