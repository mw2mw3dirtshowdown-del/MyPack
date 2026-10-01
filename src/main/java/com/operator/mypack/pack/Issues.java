package com.operator.mypack.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects problems found while loading a pack. A malformed file is recorded here and skipped; it never aborts the
 * installation of the rest of the pack.
 */
public final class Issues {

    public enum Level {
        WARN, ERROR
    }

    /** One problem: severity, the pack-relative file it belongs to and a human readable message. */
    public record Issue(Level level, String file, String message) {
        @Override
        public String toString() {
            return level + " " + file + ": " + message;
        }
    }

    private final List<Issue> issues = new ArrayList<>();

    public void warn(String file, String message) {
        issues.add(new Issue(Level.WARN, file, message));
    }

    public void error(String file, String message) {
        issues.add(new Issue(Level.ERROR, file, message));
    }

    public List<Issue> all() {
        return Collections.unmodifiableList(issues);
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.level() == Level.ERROR);
    }

    public long count(Level level) {
        return issues.stream().filter(i -> i.level() == level).count();
    }

    public boolean isEmpty() {
        return issues.isEmpty();
    }
}
