package com.operator.mypack.pack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Decides which packs can be loaded and in which order. A pack is rejected when its uuid or namespace is already taken,
 * when a dependency is missing or too old (rejections cascade to dependants) or when it is part of a dependency cycle.
 * Dependencies always load before the packs that need them.
 */
public final class DependencyResolver {

    /** A pack that cannot be loaded, with the reason shown to the administrator. */
    public record Rejection(PackManifest manifest, String reason) {
    }

    /** Load order for the accepted packs plus the rejected ones. */
    public record Result(List<PackManifest> ordered, List<Rejection> rejected) {
    }

    private DependencyResolver() {
    }

    public static Result resolve(Collection<PackManifest> input) {
        List<PackManifest> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing(PackManifest::namespace).thenComparing(m -> m.uuid().toString()));

        List<Rejection> rejected = new ArrayList<>();
        Map<UUID, PackManifest> byUuid = new LinkedHashMap<>();
        Map<String, PackManifest> byNamespace = new HashMap<>();
        for (PackManifest manifest : sorted) {
            if (byUuid.containsKey(manifest.uuid())) {
                rejected.add(new Rejection(manifest, "duplicate pack uuid " + manifest.uuid()
                        + " (already used by '" + byUuid.get(manifest.uuid()).name() + "')"));
                continue;
            }
            PackManifest sameNamespace = byNamespace.get(manifest.namespace());
            if (sameNamespace != null) {
                rejected.add(new Rejection(manifest, "namespace '" + manifest.namespace()
                        + "' is already used by pack '" + sameNamespace.name() + "'"));
                continue;
            }
            byUuid.put(manifest.uuid(), manifest);
            byNamespace.put(manifest.namespace(), manifest);
        }

        // Reject packs with missing / outdated dependencies until nothing changes (rejections cascade).
        boolean changed;
        do {
            changed = false;
            for (PackManifest manifest : new ArrayList<>(byUuid.values())) {
                String problem = dependencyProblem(manifest, byUuid);
                if (problem != null) {
                    rejected.add(new Rejection(manifest, problem));
                    byUuid.remove(manifest.uuid());
                    changed = true;
                }
            }
        } while (changed);

        // Kahn's algorithm with a deterministic (namespace ordered) queue.
        Map<UUID, Integer> pending = new HashMap<>();
        Map<UUID, List<UUID>> dependants = new HashMap<>();
        for (PackManifest manifest : byUuid.values()) {
            Set<UUID> deps = new LinkedHashSet<>();
            for (PackManifest.Dependency dep : manifest.dependencies()) {
                deps.add(dep.uuid());
            }
            pending.put(manifest.uuid(), deps.size());
            for (UUID dep : deps) {
                dependants.computeIfAbsent(dep, k -> new ArrayList<>()).add(manifest.uuid());
            }
        }
        Comparator<PackManifest> order = Comparator.comparing(PackManifest::namespace);
        PriorityQueue<PackManifest> ready = new PriorityQueue<>(order);
        for (PackManifest manifest : byUuid.values()) {
            if (pending.get(manifest.uuid()) == 0) {
                ready.add(manifest);
            }
        }
        List<PackManifest> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            PackManifest next = ready.poll();
            ordered.add(next);
            for (UUID dependant : dependants.getOrDefault(next.uuid(), List.of())) {
                if (pending.merge(dependant, -1, Integer::sum) == 0) {
                    ready.add(byUuid.get(dependant));
                }
            }
        }
        if (ordered.size() < byUuid.size()) {
            Set<UUID> placed = new LinkedHashSet<>();
            for (PackManifest manifest : ordered) {
                placed.add(manifest.uuid());
            }
            for (PackManifest manifest : byUuid.values()) {
                if (!placed.contains(manifest.uuid())) {
                    rejected.add(new Rejection(manifest, "dependency cycle involving this pack"));
                }
            }
        }
        return new Result(List.copyOf(ordered), List.copyOf(rejected));
    }

    private static String dependencyProblem(PackManifest manifest, Map<UUID, PackManifest> alive) {
        for (PackManifest.Dependency dep : manifest.dependencies()) {
            PackManifest target = alive.get(dep.uuid());
            if (target == null) {
                return "missing dependency " + dep.uuid() + " (version >= " + dep.minVersion() + ")";
            }
            if (!target.version().atLeast(dep.minVersion())) {
                return "requires '" + target.name() + "' >= " + dep.minVersion() + " but " + target.version() + " is installed";
            }
        }
        return null;
    }
}
