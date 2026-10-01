package com.operator.mypack.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyResolverTest {

    private static PackManifest pack(String ns, String uuid, String version, PackManifest.Dependency... deps) {
        return new PackManifest(2, UUID.fromString(uuid), ns, "", Version.parse(version), null, ns,
                List.of(), "", "", List.of(), List.of(deps));
    }

    private static final String A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String B = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String C = "cccccccc-0000-0000-0000-000000000003";

    private static PackManifest.Dependency dep(String uuid, String min) {
        return new PackManifest.Dependency(UUID.fromString(uuid), Version.parse(min));
    }

    private static List<String> namespaces(DependencyResolver.Result r) {
        return r.ordered().stream().map(PackManifest::namespace).toList();
    }

    @Test
    @DisplayName("dependencies load before the packs that need them")
    void ordersByDependency() {
        PackManifest base = pack("zbase", A, "1.0.0");
        PackManifest addon = pack("addon", B, "1.0.0", dep(A, "1.0.0"));
        PackManifest top = pack("atop", C, "1.0.0", dep(B, "1.0.0"));
        DependencyResolver.Result r = DependencyResolver.resolve(List.of(top, addon, base));
        assertEquals(List.of("zbase", "addon", "atop"), namespaces(r));
        assertTrue(r.rejected().isEmpty());
    }

    @Test
    @DisplayName("independent packs are ordered alphabetically (deterministic)")
    void deterministicOrder() {
        DependencyResolver.Result r = DependencyResolver.resolve(List.of(pack("m", A, "1.0.0"), pack("c", B, "1.0.0"), pack("x", C, "1.0.0")));
        assertEquals(List.of("c", "m", "x"), namespaces(r));
    }

    @Test
    @DisplayName("a missing dependency rejects the pack and cascades to its dependants")
    void cascadesMissing() {
        PackManifest orphan = pack("orphan", B, "1.0.0", dep(A, "1.0.0")); // A is not installed
        PackManifest child = pack("child", C, "1.0.0", dep(B, "1.0.0"));
        DependencyResolver.Result r = DependencyResolver.resolve(List.of(orphan, child));
        assertTrue(r.ordered().isEmpty());
        assertEquals(2, r.rejected().size());
        assertTrue(r.rejected().stream().anyMatch(x -> x.reason().contains("missing dependency")));
    }

    @Test
    @DisplayName("an outdated dependency is rejected with a readable reason")
    void rejectsOutdated() {
        PackManifest base = pack("base", A, "1.0.0");
        PackManifest addon = pack("addon", B, "1.0.0", dep(A, "2.0.0"));
        DependencyResolver.Result r = DependencyResolver.resolve(List.of(base, addon));
        assertEquals(List.of("base"), namespaces(r));
        assertTrue(r.rejected().get(0).reason().contains(">= 2.0.0"), r.rejected().get(0).reason());
    }

    @Test
    @DisplayName("dependency cycles are detected and rejected")
    void detectsCycles() {
        PackManifest a = pack("a", A, "1.0.0", dep(B, "1.0.0"));
        PackManifest b = pack("b", B, "1.0.0", dep(A, "1.0.0"));
        PackManifest ok = pack("ok", C, "1.0.0");
        DependencyResolver.Result r = DependencyResolver.resolve(List.of(a, b, ok));
        assertEquals(List.of("ok"), namespaces(r));
        assertEquals(2, r.rejected().size());
        assertTrue(r.rejected().stream().allMatch(x -> x.reason().contains("cycle")));
    }

    @Test
    @DisplayName("a duplicate uuid is rejected; the first pack in namespace order wins")
    void rejectsDuplicateUuid() {
        PackManifest first = pack("alpha", A, "1.0.0");
        PackManifest copy = pack("beta", A, "1.0.0");
        DependencyResolver.Result r = DependencyResolver.resolve(List.of(copy, first));
        assertEquals(List.of("alpha"), namespaces(r));
        assertEquals(1, r.rejected().size());
        assertEquals("beta", r.rejected().get(0).manifest().namespace());
        assertTrue(r.rejected().get(0).reason().contains("duplicate pack uuid"), r.rejected().get(0).reason());
    }

    @Test
    @DisplayName("two packs claiming one namespace: exactly one is loaded")
    void rejectsNamespaceCollision() {
        PackManifest one = pack("same", A, "1.0.0");
        PackManifest two = pack("same", B, "1.0.0");
        DependencyResolver.Result r = DependencyResolver.resolve(List.of(two, one));
        assertEquals(1, r.ordered().size());
        assertEquals(1, r.rejected().size());
        assertTrue(r.rejected().get(0).reason().contains("namespace 'same' is already used"), r.rejected().get(0).reason());
        // deterministic: the lower uuid is processed first and therefore wins
        assertEquals(UUID.fromString(A), r.ordered().get(0).uuid());
    }
}
