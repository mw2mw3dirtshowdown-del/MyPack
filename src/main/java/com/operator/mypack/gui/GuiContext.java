package com.operator.mypack.gui;

import com.operator.mypack.config.LangManager;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.services.FurnitureService;
import com.operator.mypack.services.ItemService;
import com.operator.mypack.services.MobService;
import com.operator.mypack.services.PackService;
import com.operator.mypack.tasks.Schedulers;

/** The services every GUI needs, passed in as one argument instead of eight. */
public record GuiContext(
        LangManager lang,
        Schedulers schedulers,
        ContentRegistry registry,
        ItemService items,
        MobService mobs,
        FurnitureService furniture,
        PackService packs) {
}
