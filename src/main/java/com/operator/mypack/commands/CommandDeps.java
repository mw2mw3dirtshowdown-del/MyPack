package com.operator.mypack.commands;

import com.operator.mypack.config.ConfigManager;
import com.operator.mypack.config.LangManager;
import com.operator.mypack.database.AuditRepository;
import com.operator.mypack.database.DatabaseManager;
import com.operator.mypack.gui.GuiContext;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.services.FurnitureService;
import com.operator.mypack.services.ItemService;
import com.operator.mypack.services.LootService;
import com.operator.mypack.services.MobService;
import com.operator.mypack.services.ModelService;
import com.operator.mypack.services.PackService;
import com.operator.mypack.services.ResourcePackService;
import com.operator.mypack.tasks.Schedulers;
import org.bukkit.plugin.java.JavaPlugin;

/** Everything the command tree needs, passed in explicitly (manual dependency injection). */
public record CommandDeps(
        JavaPlugin plugin,
        ConfigManager config,
        LangManager lang,
        Schedulers schedulers,
        ContentRegistry registry,
        PackService packs,
        ItemService items,
        LootService loot,
        MobService mobs,
        FurnitureService furniture,
        ModelService models,
        ResourcePackService resourcePack,
        DatabaseManager database,
        AuditRepository audit,
        GuiContext gui) {
}
