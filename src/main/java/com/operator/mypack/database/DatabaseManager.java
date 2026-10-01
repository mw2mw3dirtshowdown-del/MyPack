package com.operator.mypack.database;

import com.operator.mypack.config.Settings;
import com.operator.mypack.utils.FileUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the HikariCP pool. SQLite is the zero-setup default; MySQL and PostgreSQL are selected in {@code config.yml}.
 *
 * <p>All access is blocking JDBC, so callers on the main or region threads must hop to the async scheduler first
 * (see {@code Schedulers#supplyAsync}).</p>
 */
public final class DatabaseManager implements AutoCloseable {

    private final Logger log;
    private final ClassLoader driverParent;
    private volatile HikariDataSource dataSource;
    private volatile Dialect dialect = Dialect.SQLITE;
    private volatile String prefix = "mypack_";

    public DatabaseManager(Logger log, ClassLoader driverParent) {
        this.log = log;
        this.driverParent = driverParent;
    }

    /** Creates the pool and applies pending migrations. Throws if the database is unusable. */
    public void connect(Settings.Database cfg, Path dataFolder) throws SQLException, IOException {
        close();
        Dialect selected = Dialect.of(cfg.type());
        Files.createDirectories(dataFolder);
        if (!ExternalDriverLoader.ensureAvailable(selected, dataFolder.resolve("lib"), driverParent, log)) {
            throw new SQLException("JDBC driver " + selected.driverClass() + " is not available. "
                    + (selected == Dialect.POSTGRESQL
                    ? "Put the PostgreSQL driver jar into plugins/MyPack/lib/."
                    : "Your server does not bundle it; put the driver jar into plugins/MyPack/lib/."));
        }

        Path sqliteFile = null;
        if (selected == Dialect.SQLITE) {
            sqliteFile = FileUtils.resolveInside(dataFolder, cfg.sqliteFile());
            Files.createDirectories(sqliteFile.getParent());
        }

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("MyPack-" + selected.name().toLowerCase());
        hikari.setJdbcUrl(selected.jdbcUrl(cfg, sqliteFile));
        // The driver class is only pinned for drivers the server provides; a driver loaded from lib/ is found
        // through the registered shim instead.
        if (isBundled(selected)) {
            hikari.setDriverClassName(selected.driverClass());
        }
        if (selected != Dialect.SQLITE) {
            if (cfg.password().isEmpty()) {
                log.warning("database.password is empty - set one in config.yml for your " + selected.name().toLowerCase() + " database.");
            }
            hikari.setUsername(cfg.username());
            hikari.setPassword(cfg.password());
            hikari.setMaximumPoolSize(cfg.maxPoolSize());
            hikari.setMinimumIdle(Math.min(cfg.minIdle(), cfg.maxPoolSize()));
        } else {
            // SQLite serialises writers; a handful of connections is plenty (WAL allows concurrent readers).
            hikari.setMaximumPoolSize(Math.min(4, cfg.maxPoolSize()));
            hikari.setMinimumIdle(1);
        }
        hikari.setConnectionTimeout(cfg.connectionTimeoutMs());
        hikari.setMaxLifetime(cfg.maxLifetimeMs());

        HikariDataSource pool = new HikariDataSource(hikari);
        try {
            List<Migrator.Migration> applied = Migrator.migrate(pool, cfg.tablePrefix(), log);
            if (!applied.isEmpty()) {
                log.info("Database schema is now at version " + applied.get(applied.size() - 1).version());
            }
        } catch (SQLException | IOException | RuntimeException e) {
            pool.close();
            throw e;
        }
        this.dialect = selected;
        this.prefix = cfg.tablePrefix();
        this.dataSource = pool;
        log.info("Database connected (" + selected.name().toLowerCase() + ")");
    }

    private boolean isBundled(Dialect selected) {
        try {
            Class.forName(selected.driverClass(), false, driverParent);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public boolean isConnected() {
        HikariDataSource ds = dataSource;
        return ds != null && !ds.isClosed();
    }

    public DataSource dataSource() {
        HikariDataSource ds = dataSource;
        if (ds == null) {
            throw new IllegalStateException("database is not connected");
        }
        return ds;
    }

    public Dialect dialect() {
        return dialect;
    }

    /** Full table name for a logical table (prefix applied). */
    public String table(String logicalName) {
        return prefix + logicalName;
    }

    /** Runs {@code work} on a pooled connection (auto-commit on). Blocking - never call from a game thread. */
    public <T> T run(SqlFunction<Connection, T> work) throws SQLException {
        try (Connection connection = dataSource().getConnection()) {
            return work.apply(connection);
        }
    }

    /** Runs {@code work} inside one transaction; rolls back when it throws. Blocking. */
    public <T> T transaction(SqlFunction<Connection, T> work) throws SQLException {
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    @Override
    public void close() {
        HikariDataSource ds = dataSource;
        dataSource = null;
        if (ds != null && !ds.isClosed()) {
            try {
                ds.close();
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Error while closing the database pool", e);
            }
        }
    }
}
