package com.operator.mypack.content.loot;

import com.operator.mypack.content.loot.LootTable.Condition;
import com.operator.mypack.content.loot.LootTable.Entry;
import com.operator.mypack.content.loot.LootTable.LootFunction;
import com.operator.mypack.content.loot.LootTable.Pool;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Rolls loot tables. This class is pure (no Bukkit types, randomness is injected) so the distribution can be unit
 * tested with a seeded generator; the Bukkit layer turns the resulting {@link Drop}s into item stacks.
 */
public final class LootEvaluator {

    /** Hard cap for nested {@code loot_table} entries so a table that references itself cannot loop forever. */
    public static final int MAX_DEPTH = 8;
    /** Hard cap for the number of drops produced by one roll. */
    public static final int MAX_DROPS = 256;

    /** Circumstances of the kill. */
    public record Context(boolean killedByPlayer, int lootingLevel) {
        public static final Context NONE = new Context(false, 0);
    }

    /** One rolled stack: item id, amount and optional presentation overrides. */
    public record Drop(String item, int amount, String name, List<String> lore) {
    }

    /** Resolves nested tables by full identifier. */
    @FunctionalInterface
    public interface TableLookup {
        LootTable find(String id);
    }

    private LootEvaluator() {
    }

    public static List<Drop> roll(LootTable table, Context ctx, RandomGenerator rng, TableLookup lookup) {
        List<Drop> out = new ArrayList<>();
        roll(table, ctx, rng, lookup, 0, out);
        return out;
    }

    private static void roll(LootTable table, Context ctx, RandomGenerator rng, TableLookup lookup, int depth, List<Drop> out) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (Pool pool : table.pools()) {
            if (!passes(pool.conditions(), ctx, rng)) {
                continue;
            }
            int rolls = pool.rolls().rollInt(rng)
                    + (int) Math.floor(pool.bonusRolls().rollInt(rng) * (double) Math.max(0, ctx.lootingLevel()));
            for (int i = 0; i < rolls && out.size() < MAX_DROPS; i++) {
                Entry entry = pick(pool.entries(), ctx, rng);
                if (entry == null) {
                    continue;
                }
                switch (entry.type()) {
                    case EMPTY -> {
                        // an explicit "nothing" result
                    }
                    case TABLE -> {
                        LootTable nested = lookup == null ? null : lookup.find(entry.name());
                        if (nested != null) {
                            roll(nested, ctx, rng, lookup, depth + 1, out);
                        }
                    }
                    case ITEM -> {
                        Drop drop = item(entry, ctx, rng);
                        if (drop != null) {
                            out.add(drop);
                        }
                    }
                }
            }
        }
    }

    /** Weighted pick among the entries whose conditions pass. */
    static Entry pick(List<Entry> entries, Context ctx, RandomGenerator rng) {
        long total = 0;
        List<Entry> eligible = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            if (passes(entry.conditions(), ctx, rng)) {
                eligible.add(entry);
                total += entry.weight();
            }
        }
        if (eligible.isEmpty() || total <= 0) {
            return null;
        }
        long target = rng.nextLong(total);
        long cumulative = 0;
        for (Entry entry : eligible) {
            cumulative += entry.weight();
            if (target < cumulative) {
                return entry;
            }
        }
        return eligible.get(eligible.size() - 1);
    }

    private static Drop item(Entry entry, Context ctx, RandomGenerator rng) {
        int amount = 1;
        String name = null;
        List<String> lore = null;
        for (LootFunction function : entry.functions()) {
            if (function instanceof LootFunction.SetCount set) {
                amount = set.count().rollInt(rng);
            } else if (function instanceof LootFunction.LootingEnchant looting) {
                int bonus = 0;
                for (int level = 0; level < Math.max(0, ctx.lootingLevel()); level++) {
                    bonus += looting.perLevel().rollInt(rng);
                }
                if (looting.limit() > 0) {
                    bonus = Math.min(bonus, looting.limit() - amount);
                }
                amount += Math.max(0, bonus);
            } else if (function instanceof LootFunction.SetName setName) {
                name = setName.name();
            } else if (function instanceof LootFunction.SetLore setLore) {
                lore = setLore.lore();
            }
        }
        if (amount <= 0) {
            return null;
        }
        return new Drop(entry.name(), Math.min(amount, 1024), name, lore);
    }

    static boolean passes(List<Condition> conditions, Context ctx, RandomGenerator rng) {
        for (Condition condition : conditions) {
            boolean ok = switch (condition) {
                case Condition.KilledByPlayer k -> ctx.killedByPlayer();
                case Condition.RandomChance r -> rng.nextDouble() < r.chance();
                case Condition.RandomChanceWithLooting r ->
                        rng.nextDouble() < r.chance() + Math.max(0, ctx.lootingLevel()) * r.lootingMultiplier();
                case Condition.Never n -> false;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
