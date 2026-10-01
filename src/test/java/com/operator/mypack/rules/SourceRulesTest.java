package com.operator.mypack.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Mechanical checks of project rules that are easy to violate by accident. */
class SourceRulesTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private record Line(Path file, int number, String text) {
        @Override
        public String toString() {
            return file + ":" + number + ": " + text.strip();
        }
    }

    private static List<Line> lines() throws IOException {
        assumeTrue(Files.isDirectory(SOURCES), "run from the project root to scan the sources");
        List<Line> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> all = Files.readAllLines(file);
                for (int i = 0; i < all.size(); i++) {
                    String text = all.get(i);
                    String stripped = text.strip();
                    if (stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("/*")) {
                        continue; // comments and javadoc may mention forbidden APIs
                    }
                    out.add(new Line(file, i + 1, text));
                }
            }
        }
        return out;
    }

    private static List<Line> matching(String regex) throws IOException {
        Pattern pattern = Pattern.compile(regex);
        return lines().stream().filter(l -> pattern.matcher(l.text()).find()).toList();
    }

    @Test
    @DisplayName("no legacy colour API: ChatColor and alternate colour codes are never used")
    void noLegacyColours() throws IOException {
        List<Line> offenders = matching("ChatColor|translateAlternateColorCodes|LegacyComponentSerializer");
        assertTrue(offenders.isEmpty(), "legacy colour usage: " + offenders);
    }

    @Test
    @DisplayName("the legacy BukkitScheduler is never used (it throws on Folia)")
    void noBukkitScheduler() throws IOException {
        List<Line> offenders = matching("getScheduler\\(\\)\\.|BukkitRunnable|BukkitScheduler|runTaskTimer|runTaskLater|runTaskAsynchronously");
        // Entity#getScheduler() is the Folia-safe entity scheduler; only the server-wide BukkitScheduler is forbidden.
        offenders = offenders.stream().filter(l -> !l.text().contains("entity.getScheduler()") && !l.text().contains("e.getScheduler()")).toList();
        assertTrue(offenders.isEmpty(), "BukkitScheduler usage: " + offenders);
    }

    @Test
    @DisplayName("no blocking sleeps and no raw threads outside the HTTP server's own thread factory")
    void noSleepsOrRawThreads() throws IOException {
        List<Line> offenders = matching("Thread\\.sleep|new Thread\\(|\\.join\\(\\)");
        offenders = offenders.stream().filter(l -> !l.file().toString().endsWith("ResourcePackServer.java")).toList();
        assertTrue(offenders.isEmpty(), "blocking constructs: " + offenders);
    }

    @Test
    @DisplayName("no mutable static state (static fields are constants only)")
    void noMutableStatics() throws IOException {
        Pattern staticField = Pattern.compile("^\\s*(?:public |private |protected )?static\\s+(?!final\\b)(?!class\\b)(?!record\\b)(?!interface\\b)(?!enum\\b)[^(=;]*[=;]");
        List<Line> offenders = lines().stream().filter(l -> staticField.matcher(l.text()).find() && !l.text().contains("(")).toList();
        assertTrue(offenders.isEmpty(), "mutable static fields: " + offenders);
    }

    @Test
    @DisplayName("Folia safety: entities are never moved with the synchronous teleport")
    void noSynchronousTeleport() throws IOException {
        List<Line> offenders = matching("\\.teleport\\(");
        assertTrue(offenders.isEmpty(), "use teleportAsync: " + offenders);
    }

    @Test
    @DisplayName("every database statement is parameterized: no user value is concatenated into SQL")
    void sqlIsParameterized() throws IOException {
        assumeTrue(Files.isDirectory(SOURCES), "run from the project root to scan the sources");
        Pattern sqlLiteral = Pattern.compile("\"\\s*(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP)\\b");
        // concatenation is only allowed for table names (db.table(...)), the fixed prefix and developer constants
        Pattern concatenation = Pattern.compile("\"\\s*\\+\\s*(?!db\\.table|prefix|table|columns|marks|String\\.join|migration)[a-z]");
        List<String> offenders = new ArrayList<>();
        List<Line> all = lines().stream().filter(l -> l.file().toString().contains("/database/")).toList();
        for (int i = 0; i < all.size(); i++) {
            Line line = all.get(i);
            if (!concatenation.matcher(line.text()).find()) {
                continue;
            }
            boolean sql = false;
            for (int back = Math.max(0, i - 3); back <= i; back++) {
                sql |= sqlLiteral.matcher(all.get(back).text()).find();
            }
            if (sql) {
                offenders.add(line.toString());
            }
        }
        assertTrue(offenders.isEmpty(), "possible SQL concatenation: " + offenders);
    }
}
