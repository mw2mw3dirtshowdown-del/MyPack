package com.operator.mypack;

import com.operator.mypack.api.DefaultMyPackApi;
import com.operator.mypack.api.MyPackApi;
import com.operator.mypack.commands.CommandDeps;
import com.operator.mypack.commands.MyPackCommand;
import com.operator.mypack.config.ConfigManager;
import com.operator.mypack.config.LangManager;
import com.operator.mypack.config.Settings;
import com.operator.mypack.database.AuditRepository;
import com.operator.mypack.database.DatabaseManager;
import com.operator.mypack.database.ExternalDriverLoader;
import com.operator.mypack.database.PackRepository;
import com.operator.mypack.database.PlacedContentRepository;
import com.operator.mypack.database.PlayerStatusRepository;
import com.operator.mypack.gui.GuiContext;
import com.operator.mypack.listeners.ChunkEntityListener;
import com.operator.mypack.listeners.CraftingListener;
import com.operator.mypack.listeners.FurnitureListener;
import com.operator.mypack.listeners.GuiListener;
import com.operator.mypack.listeners.ItemListener;
import com.operator.mypack.listeners.MobListener;
import com.operator.mypack.listeners.ResourcePackListener;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.pack.Version;
import com.operator.mypack.services.ActionExecutor;
import com.operator.mypack.services.FurnitureService;
import com.operator.mypack.services.ItemService;
import com.operator.mypack.services.LootService;
import com.operator.mypack.services.MobService;
import com.operator.mypack.services.ModelService;
import com.operator.mypack.services.PackService;
import com.operator.mypack.services.RecipeService;
import com.operator.mypack.services.ResourcePackService;
import com.operator.mypack.tasks.Schedulers;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;

/**
 * MyPack: a Bedrock-style content pack system for Paper. Drop add-on packs into {@code plugins/MyPack/packs/}; the
 * plugin registers their items, recipes, loot tables, sounds, 3D-model mobs and furniture, merges their resources into
 * one resource pack, hosts it and sends it to players.
 *
 * <p>All collaborators are created here and handed to each other through constructors (manual dependency injection);
 * nothing is looked up through static state.</p>
 */
public final class MyPack extends JavaPlugin {

    /** Lowest supported server version: {@code item_model} and resource pack format 46 need 1.21.4. */
    private static final Version MINIMUM_VERSION = new Version(1, 21, 4);

    private ConfigManager configManager;
    private LangManager lang;
    private Schedulers schedulers;
    private DatabaseManager database;
    private ContentRegistry registry;
    private RecipeService recipeService;
    private ResourcePackService resourcePackService;
    private ModelService modelService;
    private MobService mobService;
    private FurnitureService furnitureService;

    @Override
    public void onEnable() {
        if (!serverVersionSupported()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // --- configuration, language, schedulers --------------------------------------------------------------
        configManager = new ConfigManager(this);
        Settings settings = configManager.load();
        lang = new LangManager(getLogger());
        lang.load(this, settings.language());
        schedulers = new Schedulers(this);

        // --- database (connect + migrate once at start-up; all later access is asynchronous) -----------------
        Path dataPath = getDataFolder().toPath();
        database = new DatabaseManager(getLogger(), getClassLoader());
        try {
            database.connect(settings.database(), dataPath);
        } catch (SQLException | IOException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not connect to the database; MyPack is disabled. Check the 'database' section of config.yml.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        PackRepository packRepository = new PackRepository(database);
        AuditRepository auditRepository = new AuditRepository(database);
        PlayerStatusRepository playerStatusRepository = new PlayerStatusRepository(database);
        PlacedContentRepository placedContentRepository = new PlacedContentRepository(database);

        // --- services ----------------------------------------------------------------------------------------
        registry = new ContentRegistry();
        ItemService itemService = new ItemService(this, registry);
        LootService lootService = new LootService(registry, itemService, getLogger());
        recipeService = new RecipeService(itemService, getLogger());
        resourcePackService = new ResourcePackService(configManager, lang, schedulers, playerStatusRepository, dataPath, getLogger());
        modelService = new ModelService(this, registry, configManager, schedulers);
        mobService = new MobService(this, registry, modelService, configManager, schedulers, placedContentRepository);
        furnitureService = new FurnitureService(this, registry, modelService, itemService, configManager, schedulers,
                placedContentRepository);
        PackService packService = new PackService(configManager, schedulers, registry, packRepository, auditRepository,
                recipeService, resourcePackService, dataPath, getLogger());
        // The model service must be notified first: it drops the blueprint cache the other two rebuild from.
        packService.addListener(modelService);
        packService.addListener(mobService);
        packService.addListener(furnitureService);
        ActionExecutor actionExecutor = new ActionExecutor(configManager, schedulers, mobService, modelService, getLogger());
        GuiContext gui = new GuiContext(lang, schedulers, registry, itemService, mobService, furnitureService, packService);

        // --- listeners ---------------------------------------------------------------------------------------
        ChunkEntityListener chunkListener = new ChunkEntityListener(mobService, furnitureService, modelService, schedulers);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        getServer().getPluginManager().registerEvents(new ItemListener(itemService, actionExecutor), this);
        getServer().getPluginManager().registerEvents(new CraftingListener(itemService, recipeService), this);
        getServer().getPluginManager().registerEvents(new MobListener(registry, mobService, modelService, lootService), this);
        getServer().getPluginManager().registerEvents(
                new FurnitureListener(registry, itemService, furnitureService, lang, configManager, gui), this);
        getServer().getPluginManager().registerEvents(chunkListener, this);
        getServer().getPluginManager().registerEvents(
                new ResourcePackListener(configManager, schedulers, resourcePackService, recipeService), this);

        // --- commands (Brigadier through the lifecycle API) ---------------------------------------------------
        CommandDeps deps = new CommandDeps(this, configManager, lang, schedulers, registry, packService, itemService, lootService,
                mobService, furnitureService, modelService, resourcePackService, database, auditRepository, gui);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(MyPackCommand.build(deps), "MyPack content packs", List.of("mp")));

        // --- public API ---------------------------------------------------------------------------------------
        MyPackApi api = new DefaultMyPackApi(registry, itemService, lootService, mobService, packService, resourcePackService);
        getServer().getServicesManager().register(MyPackApi.class, api, this, ServicePriority.Normal);

        // --- start work ---------------------------------------------------------------------------------------
        modelService.start();
        resourcePackService.startServer();
        logStatus(settings);
        packService.reload("console").whenComplete((report, error) -> {
            if (error != null) {
                getLogger().log(Level.SEVERE, "The initial pack load failed", error);
                return;
            }
            // chunks around spawn loaded before the packs did: restore their mobs / furniture now
            schedulers.global(chunkListener::scanLoadedChunks);
        });
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        if (modelService != null) {
            modelService.detachAllNow();
        }
        if (mobService != null) {
            mobService.clearTracking();
        }
        if (furnitureService != null) {
            furnitureService.shutdown();
        }
        if (recipeService != null) {
            try {
                recipeService.removeAll(false);
            } catch (RuntimeException e) {
                getLogger().fine("Recipes could not be removed during shutdown: " + e);
            }
        }
        if (resourcePackService != null) {
            resourcePackService.stop();
        }
        if (schedulers != null) {
            schedulers.cancelAll();
        }
        getServer().getServicesManager().unregisterAll(this);
        if (database != null) {
            database.close();
        }
        ExternalDriverLoader.shutdown();
    }

    // ------------------------------------------------------------------ helpers

    private boolean serverVersionSupported() {
        String raw = Bukkit.getMinecraftVersion();
        Optional<Version> version = Version.tryParse(raw);
        if (version.isEmpty()) {
            getLogger().warning("Could not parse the server version '" + raw + "'; assuming it is supported.");
            return true;
        }
        if (version.get().compareTo(MINIMUM_VERSION) < 0) {
            getLogger().severe("MyPack needs Minecraft " + MINIMUM_VERSION + " or newer (item_model and resource pack format 46), "
                    + "but this server runs " + raw + ". The plugin is disabled.");
            return false;
        }
        return true;
    }

    /** One start-up summary: scheduler flavour, database and what is hosted where. */
    private void logStatus(Settings settings) {
        getLogger().info("Server " + Bukkit.getMinecraftVersion() + " (" + (schedulers.isFolia() ? "Folia" : "Paper")
                + " schedulers), database " + settings.database().type().name().toLowerCase() + ", packs directory '"
                + settings.packs().directory() + "'.");
        getLogger().info("Integrations: none required (no soft-dependencies); other plugins use the MyPackApi service.");
    }
}
