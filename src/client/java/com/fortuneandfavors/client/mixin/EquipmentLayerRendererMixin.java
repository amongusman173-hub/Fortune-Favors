package com.fortuneandfavors.client.mixin;

import com.fortuneandfavors.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Set;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The mod's armor wears its own textures on a client with the mod: when an item is one of ours,
 * its layers are drawn from fortuneandfavors:equipment/<type> instead of the vanilla material it
 * is built on. Client-side only - the item and the server are untouched, so vanilla clients keep
 * seeing the base material.
 */
@Mixin(EquipmentLayerRenderer.class)
public abstract class EquipmentLayerRendererMixin {
   /** The armor types that ship a worn texture (see tools/make_worn_armor.py). */
   @Unique
   private static final Set<String> FF_WORN = Set.of(
      "wither_crown", "sovereigns_crown", "possessed_mask", "slime_boots", "stoneheart", "glacier_cloak",
      "mind_shroud", "automaton_armor", "astral_mantle", "colossus_plate", "sculk_sensor_leggings",
      "warlord_cloak", "evoker_cloak", "illusioner_cloak", "wardens_mantle", "potion_belt"
   );

   @Shadow
   public abstract <S> void renderLayers(
      EquipmentClientInfo.LayerType layerType, ResourceKey<EquipmentAsset> asset, Model<? super S> model, S state, ItemStack stack,
      PoseStack pose, SubmitNodeCollector collector, int light, Identifier texture, int outline, int color
   );

   @Inject(
      method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
      at = @At("HEAD"),
      cancellable = true
   )
   @SuppressWarnings("unchecked")
   private <S> void fortuneandfavors$wornTexture(
      EquipmentClientInfo.LayerType layerType, ResourceKey<EquipmentAsset> asset, Model<? super S> model, S state, ItemStack stack,
      PoseStack pose, SubmitNodeCollector collector, int light, Identifier texture, int outline, int color, CallbackInfo ci
   ) {
      // Ours already (this is the redirected call): draw it as asked.
      if ("fortuneandfavors".equals(asset.identifier().getNamespace())) {
         return;
      }
      String type = ModItems.typeOf(stack);
      if (type == null || !FF_WORN.contains(type)) {
         return;
      }
      ResourceKey<EquipmentAsset> ours = ResourceKey.create(
         (ResourceKey<? extends Registry<EquipmentAsset>>)EquipmentAssets.ROOT_ID, Identifier.fromNamespaceAndPath("fortuneandfavors", type)
      );
      this.renderLayers(layerType, ours, model, state, stack, pose, collector, light, texture, outline, color);
      ci.cancel();
   }
}
