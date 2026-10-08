package com.fortuneandfavors.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Hud.class)
public interface HudInvoker {
   @Invoker("extractTextureOverlay")
   void fortuneandfavors$extractTextureOverlay(GuiGraphicsExtractor var1, Identifier var2, float var3);
}
