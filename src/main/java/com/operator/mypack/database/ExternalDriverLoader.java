package com.operator.mypack.database;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Makes a JDBC driver available even when the server does not bundle it. Paper ships SQLite and MySQL drivers but
 * not PostgreSQL, so administrators drop the driver jar into {@code plugins/MyPack/lib/} and this class loads it in an
 * isolated class loader and registers a thin {@link Driver} shim with {@link DriverManager} (the standard way to expose
 * a driver that lives outside the caller's class loader).
 */
public final class ExternalDriverLoader {

    private static final List<URLClassLoader> LOADERS = new CopyOnWriteArrayList<>();
    private static final List<Driver> SHIMS = new CopyOnWriteArrayList<>();

    private ExternalDriverLoader() {
    }

    /**
     * @return {@code true} when the dialect's driver class can be used, either because the server already provides it
     * or because it was loaded from a jar in {@code libDirectory}
     */
    public static boolean ensureAvailable(Dialect dialect, Path libDirectory, ClassLoader parent, Logger log) {
        String driverClass = dialect.driverClass();
        try {
            Class.forName(driverClass, true, parent);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            // not bundled - look for an external jar below
        }
        if (!Files.isDirectory(libDirectory)) {
            return false;
        }
        List<URL> urls = new ArrayList<>();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(libDirectory, "*.jar")) {
            for (Path jar : jars) {
                urls.add(jar.toUri().toURL());
            }
        } catch (IOException e) {
            log.warning("Could not scan " + libDirectory + ": " + e.getMessage());
            return false;
        }
        if (urls.isEmpty()) {
            return false;
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), parent);
        try {
            Class<?> type = Class.forName(driverClass, true, loader);
            Driver real = (Driver) type.getDeclaredConstructor().newInstance();
            Driver shim = new DriverShim(real);
            DriverManager.registerDriver(shim);
            LOADERS.add(loader);
            SHIMS.add(shim);
            log.info("Loaded JDBC driver " + driverClass + " from " + libDirectory);
            return true;
        } catch (ReflectiveOperationException | SQLException | LinkageError e) {
            closeQuietly(loader);
            log.warning("Found jars in " + libDirectory + " but could not load " + driverClass + ": " + e);
            return false;
        }
    }

    /** Unregisters every shim and closes the isolated class loaders (called from {@code onDisable}). */
    public static void shutdown() {
        for (Driver shim : SHIMS) {
            try {
                DriverManager.deregisterDriver(shim);
            } catch (SQLException ignored) {
                // nothing sensible to do while shutting down
            }
        }
        SHIMS.clear();
        for (URLClassLoader loader : LOADERS) {
            closeQuietly(loader);
        }
        LOADERS.clear();
    }

    /** Number of drivers currently loaded from external jars (used by tests and {@code /mypack debug}). */
    public static int loadedDriverCount() {
        return SHIMS.size();
    }

    private static void closeQuietly(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Delegating driver that lives in the plugin's class loader so DriverManager accepts it for our callers. */
    static final class DriverShim implements Driver {
        private final Driver delegate;

        DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        Driver delegate() {
            return delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
