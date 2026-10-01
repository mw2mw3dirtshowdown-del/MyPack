package com.operator.mypack.model.anim;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Values an expression can read: {@code query.*} (supplied by the model runtime) and {@code variable.*} /
 * {@code temp.*} (assignable from the expression itself). Unknown names read as 0, like in Bedrock.
 */
public final class MolangContext {

    /**
     * Thread-safe default generator. {@code RandomGenerator.getDefault()} is deliberately avoided: it needs the optional
     * {@code jdk.random} module, which minimal runtimes do not contain.
     */
    private static final RandomGenerator SHARED = new RandomGenerator() {
        @Override
        public long nextLong() {
            return ThreadLocalRandom.current().nextLong();
        }
    };

    private final Map<String, Double> queries = new HashMap<>();
    private final Map<String, Double> variables = new HashMap<>();
    private final RandomGenerator random;

    public MolangContext() {
        this(SHARED);
    }

    public MolangContext(RandomGenerator random) {
        this.random = random;
    }

    public RandomGenerator random() {
        return random;
    }

    public MolangContext setQuery(String name, double value) {
        queries.put(name.toLowerCase(Locale.ROOT), value);
        return this;
    }

    public double query(String name) {
        return queries.getOrDefault(name, 0.0D);
    }

    public void setVariable(String name, double value) {
        variables.put(name.toLowerCase(Locale.ROOT), value);
    }

    public double variable(String name) {
        return variables.getOrDefault(name, 0.0D);
    }

    /** Clears the per-evaluation variables (queries are kept). */
    public void clearVariables() {
        variables.clear();
    }
}
