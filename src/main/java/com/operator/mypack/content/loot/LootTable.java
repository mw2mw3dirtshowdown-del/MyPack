package com.operator.mypack.content.loot;

import com.operator.mypack.content.ContentId;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * A loot table in the Bedrock layout: pools that roll a number of times and pick weighted entries. Entry selection is
 * by <em>weight</em> (relative likelihood), never by percentage.
 */
public record LootTable(ContentId id, List<Pool> pools) {

    /** Inclusive numeric range; a fixed value has {@code min == max}. */
    public record Range(double min, double max) {
        public Range {
            if (max < min) {
                double t = min;
                min = max;
                max = t;
            }
        }

        public static Range of(double value) {
            return new Range(value, value);
        }

        /** Uniform integer in {@code [round(min), round(max)]}. */
        public int rollInt(RandomGenerator rng) {
            int lo = (int) Math.round(min);
            int hi = (int) Math.round(max);
            return lo == hi ? lo : rng.nextInt(lo, hi + 1);
        }
    }

    /** One group of entries that is rolled {@code rolls} (+ bonus) times. */
    public record Pool(Range rolls, Range bonusRolls, List<Condition> conditions, List<Entry> entries) {
    }

    public enum EntryType {
        ITEM, TABLE, EMPTY
    }

    /** A weighted choice inside a pool. {@code name} is an item id or a loot table id depending on {@code type}. */
    public record Entry(EntryType type, String name, int weight, List<LootFunction> functions, List<Condition> conditions) {
    }

    /** Modifies a rolled item. */
    public sealed interface LootFunction {
        /** Sets the stack size to a random value of {@code count}. */
        record SetCount(Range count) implements LootFunction {
        }

        /** Adds {@code perLevel} items for every level of Looting on the killer's weapon (capped by {@code limit} > 0). */
        record LootingEnchant(Range perLevel, int limit) implements LootFunction {
        }

        record SetName(String name) implements LootFunction {
        }

        record SetLore(List<String> lore) implements LootFunction {
        }
    }

    /** Gates a pool or an entry. */
    public sealed interface Condition {
        record KilledByPlayer() implements Condition {
        }

        record RandomChance(double chance) implements Condition {
        }

        record RandomChanceWithLooting(double chance, double lootingMultiplier) implements Condition {
        }

        /** Stand-in for an unsupported condition: it never passes, so unknown restrictions can't make loot easier. */
        record Never() implements Condition {
        }
    }
}
