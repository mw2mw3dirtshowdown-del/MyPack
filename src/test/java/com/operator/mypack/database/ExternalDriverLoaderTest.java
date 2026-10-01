package com.operator.mypack.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalDriverLoaderTest {

    @TempDir
    Path tmp;

    @AfterEach
    void cleanup() {
        ExternalDriverLoader.shutdown();
    }

    @Test
    @DisplayName("a driver the server already provides needs no external jar")
    void bundledDriverIsUsedDirectly() {
        assertTrue(ExternalDriverLoader.ensureAvailable(Dialect.SQLITE, tmp.resolve("lib"),
                getClass().getClassLoader(), Logger.getAnonymousLogger()));
        assertEquals(0, ExternalDriverLoader.loadedDriverCount());
    }

    @Test
    @DisplayName("a missing driver without a jar in lib/ is reported as unavailable")
    void missingDriver() throws Exception {
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        assertFalse(ExternalDriverLoader.ensureAvailable(Dialect.POSTGRESQL, lib,
                ClassLoader.getPlatformClassLoader(), Logger.getAnonymousLogger()));
    }

    @Test
    @DisplayName("a driver jar dropped into lib/ is loaded in isolation and exposed through a shim")
    void loadsDriverFromJar() throws Exception {
        // Use the SQLite driver jar as a stand-in for "a driver the server does not bundle".
        Path sourceJar = Path.of(org.sqlite.JDBC.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        Files.copy(sourceJar, lib.resolve("sqlite-jdbc.jar"));
        // sqlite-jdbc needs slf4j-api next to it (pgjdbc, the real use case, has no such dependency)
        Path slf4jJar = Path.of(org.slf4j.LoggerFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.copy(slf4jJar, lib.resolve("slf4j-api.jar"));

        // Platform class loader as parent: the driver is NOT visible through delegation, only via lib/.
        boolean ok = ExternalDriverLoader.ensureAvailable(Dialect.SQLITE, lib, ClassLoader.getPlatformClassLoader(),
                Logger.getAnonymousLogger());

        assertTrue(ok);
        assertEquals(1, ExternalDriverLoader.loadedDriverCount());
        ExternalDriverLoader.DriverShim shim = Collections.list(DriverManager.getDrivers()).stream()
                .filter(d -> d instanceof ExternalDriverLoader.DriverShim)
                .map(d -> (ExternalDriverLoader.DriverShim) d)
                .findFirst().orElseThrow();
        Driver real = shim.delegate();
        assertEquals("org.sqlite.JDBC", real.getClass().getName());
        assertNotSame(org.sqlite.JDBC.class, real.getClass(), "the driver must come from its own class loader");
        assertTrue(shim.acceptsURL("jdbc:sqlite::memory:"));
        assertFalse(shim.acceptsURL("jdbc:mysql://localhost/x"));

        ExternalDriverLoader.shutdown();
        assertEquals(0, ExternalDriverLoader.loadedDriverCount());
        assertTrue(Collections.list(DriverManager.getDrivers()).stream().noneMatch(d -> d instanceof ExternalDriverLoader.DriverShim));
    }
}
