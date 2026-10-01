package com.operator.mypack.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.operator.mypack.config.LangManager;
import com.operator.mypack.config.Settings;
import com.operator.mypack.content.loot.LootEvaluator;
import com.operator.mypack.content.mob.MobDefinition;
import com.operator.mypack.database.Dialect;
import com.operator.mypack.gui.MainMenuGui;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.pack.InstalledPack;
import com.operator.mypack.pack.Issues;
import com.operator.mypack.services.PackService;
import com.operator.mypack.services.ResourcePackService;
import com.operator.mypack.utils.TextUtils;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The {@code /mypack} command, registered through Paper's Brigadier lifecycle API. Every node has a permission
 * requirement (so players never even see commands they cannot use) and tab completion for its arguments.
 */
public final class MyPackCommand {

    private static final int MAX_LISTED_ISSUES = 25;
    private static final int MAX_LOOT_ROLLS = 1000;

    private final CommandDeps d;
    private final LangManager lang;

    private MyPackCommand(CommandDeps deps) {
        this.d = deps;
        this.lang = deps.lang();
    }

    public static LiteralCommandNode<CommandSourceStack> build(CommandDeps deps) {
        return new MyPackCommand(deps).root();
    }

    // ------------------------------------------------------------------ tree

    private LiteralCommandNode<CommandSourceStack> root() {
        return Commands.literal("mypack")
                .requires(src -> src.getSender().hasPermission("mypack.use"))
                .executes(ctx -> help(sender(ctx)))
                .then(Commands.literal("help").executes(ctx -> help(sender(ctx))))
                .then(node("reload", "mypack.reload").executes(this::reload))
                .then(node("list", "mypack.list").executes(this::list))
                .then(node("info", "mypack.info")
                        .then(Commands.argument("pack", StringArgumentType.string())
                                .suggests(suggest(this::packRefs)).executes(this::info)))
                .then(node("validate", "mypack.validate")
                        .executes(ctx -> validate(ctx, null))
                        .then(Commands.argument("pack", StringArgumentType.string())
                                .suggests(suggest(this::packRefs))
                                .executes(ctx -> validate(ctx, StringArgumentType.getString(ctx, "pack")))))
                .then(node("install", "mypack.install")
                        .then(Commands.argument("file", StringArgumentType.string())
                                .suggests(suggest(this::installable)).executes(this::install)))
                .then(node("uninstall", "mypack.install")
                        .then(Commands.argument("pack", StringArgumentType.string())
                                .suggests(suggest(this::packRefs)).executes(this::uninstall)))
                .then(node("enable", "mypack.install")
                        .then(Commands.argument("pack", StringArgumentType.string())
                                .suggests(suggest(this::packRefs)).executes(ctx -> enable(ctx, true))))
                .then(node("disable", "mypack.install")
                        .then(Commands.argument("pack", StringArgumentType.string())
                                .suggests(suggest(this::packRefs)).executes(ctx -> enable(ctx, false))))
                .then(node("give", "mypack.give")
                        .then(Commands.argument("player", ArgumentTypes.player())
                                .then(Commands.argument("item", ArgumentTypes.namespacedKey())
                                        .suggests(suggest(this::giveable))
                                        .executes(ctx -> give(ctx, 1))
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "amount")))))))
                .then(node("spawn", "mypack.spawn")
                        .then(Commands.argument("mob", ArgumentTypes.namespacedKey())
                                .suggests(suggest(this::mobIds)).executes(this::spawn)))
                .then(node("loot", "mypack.loot")
                        .then(Commands.literal("roll")
                                .then(Commands.argument("table", ArgumentTypes.namespacedKey())
                                        .suggests(suggest(this::lootIds))
                                        .executes(ctx -> lootRoll(ctx, 1))
                                        .then(Commands.argument("times", IntegerArgumentType.integer(1, MAX_LOOT_ROLLS))
                                                .executes(ctx -> lootRoll(ctx, IntegerArgumentType.getInteger(ctx, "times")))))))
                .then(node("browse", "mypack.gui").executes(this::browse))
                .then(node("resourcepack", "mypack.resourcepack")
                        .then(Commands.literal("status").executes(this::rpStatus))
                        .then(Commands.literal("url").executes(this::rpUrl))
                        .then(Commands.literal("rebuild").executes(this::rpRebuild))
                        .then(Commands.literal("push")
                                .executes(ctx -> rpPush(ctx, null))
                                .then(Commands.argument("player", ArgumentTypes.player())
                                        .executes(ctx -> rpPush(ctx, ctx.getArgument("player", PlayerSelectorArgumentResolver.class))))))
                .then(node("purge", "mypack.admin")
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .suggests(suggest(this::namespaces)).executes(this::purge)))
                .then(node("debug", "mypack.debug").executes(this::debug))
                .build();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> node(String name, String permission) {
        return Commands.literal(name).requires(src -> src.getSender().hasPermission(permission));
    }

    private static CommandSender sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    private static SuggestionProvider<CommandSourceStack> suggest(Supplier<Collection<String>> values) {
        return (ctx, builder) -> {
            String prefix = builder.getRemainingLowerCase();
            for (String value : values.get()) {
                if (value.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    builder.suggest(value);
                }
            }
            return builder.buildFuture();
        };
    }

    /** Runs {@code task} on the thread that owns {@code sender} (entity thread for players, global for the console). */
    private void onSender(CommandSender sender, Runnable task) {
        if (sender instanceof Entity entity) {
            d.schedulers().entity(entity, task, null);
        } else {
            d.schedulers().global(task);
        }
    }

    // ------------------------------------------------------------------ suggestion sources

    private Collection<String> packRefs() {
        List<String> out = new ArrayList<>();
        for (PackService.PackStatus status : d.packs().statuses()) {
            if (!status.namespace().equals("-")) {
                out.add(status.namespace());
            }
        }
        return out;
    }

    private Collection<String> installable() {
        List<String> out = new ArrayList<>();
        for (PackService.PackStatus status : d.packs().statuses()) {
            if (status.state() == PackService.State.NOT_INSTALLED) {
                out.add(status.source());
            }
        }
        return out;
    }

    private Collection<String> giveable() {
        List<String> out = new ArrayList<>(d.registry().snapshot().items().keySet());
        out.addAll(d.registry().snapshot().furniture().keySet());
        return out;
    }

    private Collection<String> mobIds() {
        return d.registry().snapshot().mobs().keySet();
    }

    private Collection<String> lootIds() {
        return d.registry().snapshot().lootTables().keySet();
    }

    private Collection<String> namespaces() {
        List<String> out = new ArrayList<>();
        for (InstalledPack pack : d.packs().loadedPacks()) {
            out.add(pack.namespace());
        }
        return out;
    }

    // ------------------------------------------------------------------ commands

    private int help(CommandSender sender) {
        lang.sendList(sender, "command.help");
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        Settings before = d.config().settings();
        lang.send(sender, "command.reload.start");
        d.schedulers().supplyAsync(() -> {
            d.config().load();
            lang.load(d.plugin(), d.config().settings().language());
            return null;
        }).thenCompose(ignored -> d.packs().reload(sender.getName())).whenComplete((report, error) -> onSender(sender, () -> {
            if (error != null) {
                d.plugin().getLogger().warning("Reload failed: " + error);
                lang.send(sender, "command.reload.failed", Placeholder.unparsed("error", rootMessage(error)));
                return;
            }
            lang.send(sender, "command.reload.done",
                    LangManager.number("packs", report.loaded()),
                    LangManager.number("content", report.contentCount()),
                    LangManager.number("problems", report.statuses().size() - report.loaded()));
            if (!before.resourcePack().http().equals(d.config().settings().resourcePack().http())) {
                lang.send(sender, "command.reload.restart-http");
            }
            if (!before.database().equals(d.config().settings().database())) {
                lang.send(sender, "command.reload.restart-database");
            }
            if (report.resourcePack() != null && !report.resourcePack().success()) {
                lang.send(sender, "command.reload.resourcepack-failed",
                        Placeholder.unparsed("error", String.valueOf(report.resourcePack().error())));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        List<PackService.PackStatus> statuses = d.packs().statuses();
        if (statuses.isEmpty()) {
            lang.send(sender, "command.list.empty", Placeholder.unparsed("directory", d.packs().packsDirectory().toString()));
            return Command.SINGLE_SUCCESS;
        }
        lang.send(sender, "command.list.header", LangManager.number("count", statuses.size()));
        for (PackService.PackStatus status : statuses) {
            lang.send(sender, "command.list.entry." + status.state().name().toLowerCase(Locale.ROOT),
                    Placeholder.unparsed("name", status.name()),
                    Placeholder.unparsed("namespace", status.namespace()),
                    Placeholder.unparsed("version", status.version()),
                    Placeholder.unparsed("source", status.source()),
                    Placeholder.unparsed("detail", status.detail()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        String ref = StringArgumentType.getString(ctx, "pack");
        Optional<PackService.PackStatus> found = d.packs().find(ref);
        if (found.isEmpty()) {
            lang.send(sender, "command.pack-not-found", Placeholder.unparsed("pack", ref));
            return 0;
        }
        PackService.PackStatus status = found.get();
        lang.send(sender, "command.info.header", Placeholder.unparsed("name", status.name()));
        lang.send(sender, "command.info.line", Placeholder.unparsed("key", "Source"), Placeholder.unparsed("value", status.source()));
        lang.send(sender, "command.info.line", Placeholder.unparsed("key", "State"),
                Placeholder.unparsed("value", status.state() + " - " + status.detail()));
        InstalledPack pack = status.pack();
        if (pack != null) {
            var manifest = pack.manifest();
            lang.send(sender, "command.info.line", Placeholder.unparsed("key", "Namespace"), Placeholder.unparsed("value", manifest.namespace()));
            lang.send(sender, "command.info.line", Placeholder.unparsed("key", "Version"), Placeholder.unparsed("value", manifest.version().toString()));
            lang.send(sender, "command.info.line", Placeholder.unparsed("key", "UUID"), Placeholder.unparsed("value", manifest.uuid().toString()));
            lang.send(sender, "command.info.line", Placeholder.unparsed("key", "Authors"),
                    Placeholder.unparsed("value", manifest.authors().isEmpty() ? "-" : String.join(", ", manifest.authors())));
            var c = pack.content();
            lang.send(sender, "command.info.line", Placeholder.unparsed("key", "Content"), Placeholder.unparsed("value",
                    c.items().size() + " items, " + c.recipes().size() + " recipes, " + c.lootTables().size() + " loot tables, "
                            + c.mobs().size() + " mobs, " + c.furniture().size() + " furniture, " + c.sounds().size() + " sounds, "
                            + c.geometries().size() + " models, " + c.animations().size() + " animations"));
            lang.send(sender, "command.info.line", Placeholder.unparsed("key", "Problems"),
                    Placeholder.unparsed("value", pack.issues().size() + " (see /mypack validate " + manifest.namespace() + ")"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int validate(CommandContext<CommandSourceStack> ctx, String ref) {
        CommandSender sender = sender(ctx);
        List<InstalledPack> targets = new ArrayList<>();
        if (ref == null) {
            targets.addAll(d.packs().loadedPacks());
        } else {
            Optional<PackService.PackStatus> found = d.packs().find(ref);
            if (found.isEmpty() || found.get().pack() == null) {
                lang.send(sender, "command.pack-not-found", Placeholder.unparsed("pack", ref));
                return 0;
            }
            targets.add(found.get().pack());
        }
        int shown = 0;
        int total = 0;
        for (InstalledPack pack : targets) {
            for (Issues.Issue issue : pack.issues()) {
                total++;
                if (shown < MAX_LISTED_ISSUES) {
                    shown++;
                    lang.send(sender, issue.level() == Issues.Level.ERROR ? "command.validate.error" : "command.validate.warn",
                            Placeholder.unparsed("pack", pack.namespace()), Placeholder.unparsed("file", issue.file()),
                            Placeholder.unparsed("message", issue.message()));
                }
            }
        }
        if (total == 0) {
            lang.send(sender, "command.validate.clean", LangManager.number("packs", targets.size()));
        } else if (total > shown) {
            lang.send(sender, "command.validate.more", LangManager.number("count", total - shown));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int install(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        String file = StringArgumentType.getString(ctx, "file");
        lang.send(sender, "command.install.start", Placeholder.unparsed("file", file));
        d.packs().install(file, sender.getName()).whenComplete((report, error) -> onSender(sender, () -> {
            if (error != null) {
                lang.send(sender, "command.install.failed", Placeholder.unparsed("file", file),
                        Placeholder.unparsed("error", rootMessage(error)));
                return;
            }
            Optional<PackService.PackStatus> status = report.statuses().stream().filter(s -> s.source().equals(file)).findFirst();
            if (status.isPresent() && status.get().state() == PackService.State.LOADED) {
                lang.send(sender, "command.install.done", Placeholder.unparsed("name", status.get().name()),
                        Placeholder.unparsed("detail", status.get().detail()));
            } else {
                lang.send(sender, "command.install.not-loaded", Placeholder.unparsed("file", file),
                        Placeholder.unparsed("detail", status.map(PackService.PackStatus::detail).orElse("not found")));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    private int uninstall(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        String ref = StringArgumentType.getString(ctx, "pack");
        d.packs().uninstall(ref, sender.getName()).whenComplete((removed, error) -> onSender(sender, () -> {
            if (error != null) {
                lang.send(sender, "command.uninstall.failed", Placeholder.unparsed("error", rootMessage(error)));
            } else if (removed) {
                lang.send(sender, "command.uninstall.done", Placeholder.unparsed("pack", ref));
            } else {
                lang.send(sender, "command.pack-not-found", Placeholder.unparsed("pack", ref));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    private int enable(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        CommandSender sender = sender(ctx);
        String ref = StringArgumentType.getString(ctx, "pack");
        d.packs().setEnabled(ref, enabled, sender.getName()).whenComplete((changed, error) -> onSender(sender, () -> {
            if (error != null) {
                lang.send(sender, "command.enable.failed", Placeholder.unparsed("error", rootMessage(error)));
            } else if (changed) {
                lang.send(sender, enabled ? "command.enable.enabled" : "command.enable.disabled", Placeholder.unparsed("pack", ref));
            } else {
                lang.send(sender, "command.pack-not-found", Placeholder.unparsed("pack", ref));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    private int give(CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
        CommandSender sender = sender(ctx);
        List<Player> targets = ctx.getArgument("player", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        NamespacedKey key = ctx.getArgument("item", NamespacedKey.class);
        String id = key.asString();
        if (targets.isEmpty()) {
            lang.send(sender, "command.no-player");
            return 0;
        }
        if (d.items().createFromId(id, 1) == null) {
            lang.send(sender, "command.give.unknown", Placeholder.unparsed("item", id));
            return 0;
        }
        for (Player target : targets) {
            ItemStack stack = d.items().createFromId(id, amount);
            d.schedulers().entity(target, () -> {
                for (ItemStack leftover : target.getInventory().addItem(stack).values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), leftover);
                }
                lang.send(target, "command.give.received", Placeholder.unparsed("item", id), LangManager.number("amount", amount));
            }, null);
        }
        lang.send(sender, "command.give.done", Placeholder.unparsed("item", id), LangManager.number("amount", amount),
                LangManager.number("players", targets.size()));
        return Command.SINGLE_SUCCESS;
    }

    private int spawn(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!(sender instanceof Player player)) {
            lang.send(sender, "command.player-only");
            return 0;
        }
        String id = ctx.getArgument("mob", NamespacedKey.class).asString();
        MobDefinition definition = d.registry().mob(id);
        if (definition == null) {
            lang.send(sender, "command.spawn.unknown", Placeholder.unparsed("mob", id));
            return 0;
        }
        if (d.mobs().spawn(definition, player.getLocation()) == null) {
            lang.send(sender, "command.spawn.failed", Placeholder.unparsed("mob", id));
            return 0;
        }
        lang.send(sender, "command.spawn.done", Placeholder.unparsed("mob", id));
        return Command.SINGLE_SUCCESS;
    }

    private int lootRoll(CommandContext<CommandSourceStack> ctx, int times) {
        CommandSender sender = sender(ctx);
        String id = ctx.getArgument("table", NamespacedKey.class).asString();
        if (!d.loot().exists(id)) {
            lang.send(sender, "command.loot.unknown", Placeholder.unparsed("table", id));
            return 0;
        }
        Map<String, Integer> totals = new TreeMap<>();
        for (int i = 0; i < times; i++) {
            for (ItemStack stack : d.loot().roll(id, LootEvaluator.Context.NONE)) {
                String item = d.items().idOf(stack) != null ? d.items().idOf(stack) : "minecraft:" + stack.getType().name().toLowerCase(Locale.ROOT);
                totals.merge(item, stack.getAmount(), Integer::sum);
            }
        }
        lang.send(sender, "command.loot.header", Placeholder.unparsed("table", id), LangManager.number("times", times));
        if (totals.isEmpty()) {
            lang.send(sender, "command.loot.nothing");
        }
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            lang.send(sender, "command.loot.entry", Placeholder.unparsed("item", entry.getKey()), LangManager.number("amount", entry.getValue()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int browse(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!(sender instanceof Player player)) {
            lang.send(sender, "command.player-only");
            return 0;
        }
        new MainMenuGui(d.gui()).open(player);
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ resource pack

    private int rpStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        ResourcePackService.Published published = d.resourcePack().published();
        Settings.ResourcePack settings = d.config().settings().resourcePack();
        lang.send(sender, "command.rp.header");
        lang.send(sender, "command.rp.enabled", Placeholder.unparsed("value", String.valueOf(settings.enabled())));
        lang.send(sender, "command.rp.server", Placeholder.unparsed("value", d.resourcePack().isServerRunning() ? "running"
                : d.resourcePack().hasServerFailed() ? "FAILED to start (see console)" : "not running"));
        if (published == null) {
            lang.send(sender, "command.rp.none");
            return Command.SINGLE_SUCCESS;
        }
        lang.send(sender, "command.rp.build", Placeholder.unparsed("sha1", published.sha1()),
                LangManager.number("files", published.fileCount()), LangManager.number("kib", published.zip().length / 1024),
                LangManager.number("warnings", published.warnings().size()));
        lang.send(sender, "command.rp.url", Placeholder.unparsed("url", String.valueOf(published.url())));
        d.schedulers().async(() -> {
            try {
                Map<String, Integer> counts = d.resourcePack().statusCounts();
                onSender(sender, () -> lang.send(sender, "command.rp.clients",
                        Placeholder.unparsed("value", counts.isEmpty() ? "no data yet" : counts.toString())));
            } catch (SQLException | RuntimeException e) {
                d.plugin().getLogger().fine("Could not read resource pack statistics: " + e);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int rpUrl(CommandContext<CommandSourceStack> ctx) {
        ResourcePackService.Published published = d.resourcePack().published();
        lang.send(sender(ctx), "command.rp.url", Placeholder.unparsed("url", published == null ? "-" : String.valueOf(published.url())));
        return Command.SINGLE_SUCCESS;
    }

    private int rpRebuild(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        lang.send(sender, "command.rp.rebuilding");
        ContentRegistry.Snapshot snapshot = d.registry().snapshot();
        d.resourcePack().rebuild(d.packs().loadedPacks(), snapshot).whenComplete((report, error) -> onSender(sender, () -> {
            if (error != null || !report.success()) {
                lang.send(sender, "command.rp.rebuild-failed", Placeholder.unparsed("error",
                        error != null ? rootMessage(error) : String.valueOf(report.error())));
            } else {
                lang.send(sender, "command.rp.rebuilt", Placeholder.unparsed("sha1", report.published().sha1()),
                        Placeholder.unparsed("changed", String.valueOf(report.changed())));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    private int rpPush(CommandContext<CommandSourceStack> ctx, PlayerSelectorArgumentResolver selector) {
        CommandSender sender = sender(ctx);
        if (d.resourcePack().published() == null) {
            lang.send(sender, "command.rp.none");
            return 0;
        }
        try {
            if (selector == null) {
                lang.send(sender, "command.rp.pushed", LangManager.number("players", d.resourcePack().pushAll()));
            } else {
                List<Player> players = selector.resolve(ctx.getSource());
                for (Player player : players) {
                    d.schedulers().entity(player, () -> d.resourcePack().push(player), null);
                }
                lang.send(sender, "command.rp.pushed", LangManager.number("players", players.size()));
            }
        } catch (CommandSyntaxException e) {
            lang.send(sender, "command.no-player");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ admin

    private int purge(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        String namespace = StringArgumentType.getString(ctx, "namespace");
        int mobs = d.mobs().purgeNamespace(namespace);
        int furniture = d.furniture().purgeNamespace(namespace);
        lang.send(sender, "command.purge.done", Placeholder.unparsed("namespace", namespace),
                LangManager.number("mobs", mobs), LangManager.number("furniture", furniture));
        return Command.SINGLE_SUCCESS;
    }

    private int debug(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        ContentRegistry.Snapshot s = d.registry().snapshot();
        Dialect dialect = d.database().dialect();
        lang.send(sender, "command.debug.header");
        debugLine(sender, "Server", Bukkit.getMinecraftVersion() + (d.schedulers().isFolia() ? " (Folia)" : " (Paper)"));
        debugLine(sender, "Packs loaded", String.valueOf(d.packs().loadedPacks().size()));
        debugLine(sender, "Definitions", s.items().size() + " items, " + s.recipes().size() + " recipes, " + s.mobs().size()
                + " mobs, " + s.furniture().size() + " furniture, " + s.lootTables().size() + " loot, " + s.sounds().size()
                + " sounds, " + s.geometries().size() + " models, " + s.animations().size() + " animations");
        debugLine(sender, "Live models", d.models().count() + " (" + d.mobs().trackedCount() + " mobs, "
                + d.furniture().trackedCount() + " furniture)");
        debugLine(sender, "Database", d.database().isConnected() ? dialect.name().toLowerCase(Locale.ROOT) + " connected" : "NOT connected");
        debugLine(sender, "HTTP server", d.resourcePack().isServerRunning() ? "running" : "stopped");
        d.schedulers().async(() -> {
            try {
                var recent = d.audit().recent(5);
                onSender(sender, () -> {
                    for (var entry : recent) {
                        debugLine(sender, "Audit", entry.action() + " by " + entry.actor() + (entry.detail() == null ? "" : " - " + entry.detail()));
                    }
                });
            } catch (SQLException | RuntimeException e) {
                d.plugin().getLogger().fine("Could not read the audit log: " + e);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private void debugLine(CommandSender sender, String key, String value) {
        lang.send(sender, "command.debug.line", Placeholder.unparsed("key", key), Placeholder.unparsed("value", value));
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : TextUtils.escape(cause.getMessage());
    }
}
