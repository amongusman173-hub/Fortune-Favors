package com.fortuneandfavors.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** ModMenu entrypoint. Deliberately imports NO Cloth classes: the screen comes
 *  from {@link FfConfigManager#configScreen}, which is null when Cloth Config
 *  isn't installed (ModMenu then just shows no config button instead of
 *  crashing). This class is only instantiated by ModMenu itself, so installs
 *  without ModMenu never even load it. */
public class FfModMenuApi implements ModMenuApi {
   public ConfigScreenFactory<?> getModConfigScreenFactory() {
      return FfConfigManager::configScreen;
   }
}
