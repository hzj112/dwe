package com.athenahealth.dwe.observability;

import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** Samples durable state on a separate thread/pool; scrapes read only the immutable cached snapshot. */
@Component
@Profile("api")
@ConditionalOnProperty(name = "observability.database-enabled", havingValue = "true")
public class DatabaseSampler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DatabaseSampler.class);
    private final HikariDataSource pool;
    private final EngineTelemetry telemetry;
    private final Duration interval;
    private final long timeoutMs;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("database-observation-", 0).factory());
    private volatile Snapshot snapshot = new Snapshot(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    private volatile long lastSuccess;
    private volatile boolean latestSuccess;
    private volatile boolean running;

    public record Snapshot(double ready, double oldestReadySeconds, double awaitingPromotion,
                           double expired, double oldestExpiredSeconds) { }

    public DatabaseSampler(DataSource source, MeterRegistry registry, EngineTelemetry telemetry,
                           @Value("${observability.sample-interval:5s}") Duration interval,
                           @Value("${observability.query-timeout:500ms}") Duration timeout) throws Exception {
        if (interval.toMillis() < 250 || timeout.toMillis() < 1 || timeout.compareTo(interval) >= 0) {
            throw new IllegalArgumentException("Observation interval must be >=250ms and exceed its positive query timeout");
        }
        this.telemetry = telemetry;
        this.interval = interval;
        timeoutMs = timeout.toMillis();
        HikariDataSource application = source.unwrap(HikariDataSource.class);
        var config = new HikariConfig();
        config.setJdbcUrl(application.getJdbcUrl());
        config.setUsername(application.getUsername());
        config.setPassword(application.getPassword());
        config.setPoolName("observability");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setReadOnly(true);
        config.setConnectionTimeout(500);
        config.setValidationTimeout(500);
        config.setInitializationFailTimeout(-1);
        pool = new HikariDataSource(config);
        Gauge.builder("dwe.database.sample.last.success.timestamp.seconds", () -> lastSuccess / 1000.0).register(registry);
        Gauge.builder("dwe.database.sample.success", () -> latestSuccess ? 1 : 0).register(registry);
        Gauge.builder("dwe.steps.ready", () -> snapshot.ready()).register(registry);
        Gauge.builder("dwe.steps.oldest.ready.seconds", () -> snapshot.oldestReadySeconds()).register(registry);
        Gauge.builder("dwe.steps.awaiting.promotion", () -> snapshot.awaitingPromotion()).register(registry);
        Gauge.builder("dwe.leases.expired", () -> snapshot.expired()).register(registry);
        Gauge.builder("dwe.leases.oldest.expired.seconds", () -> snapshot.oldestExpiredSeconds()).register(registry);
    }

    /** Reads a consistent snapshot using database time; no workflow rows are locked or modified. */
    public void sample() {
        long started = System.nanoTime();
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL statement_timeout = '" + timeoutMs + "ms'");
                statement.execute("SET LOCAL lock_timeout = '" + timeoutMs + "ms'");
                try (var rows = statement.executeQuery("""
                        WITH active_steps AS MATERIALIZED (
                            SELECT s.workflow_id, s.step_id, s.status, s.eligible_at
                            FROM step_execution s JOIN workflow_execution w ON w.id = s.workflow_id
                            WHERE w.status IN ('PENDING','RUNNING')
                        ), observed AS (SELECT statement_timestamp() AS now)
                        SELECT count(*) FILTER (WHERE status IN ('PENDING','RETRYING') AND eligible_at <= observed.now),
                            coalesce(extract(epoch FROM observed.now - min(eligible_at)
                                FILTER (WHERE status IN ('PENDING','RETRYING') AND eligible_at <= observed.now)), 0),
                            count(*) FILTER (WHERE status = 'PENDING' AND eligible_at IS NULL AND NOT EXISTS (
                                SELECT 1 FROM step_dependency d JOIN step_execution p
                                  ON p.workflow_id = d.workflow_id AND p.step_id = d.depends_on_step_id
                                WHERE d.workflow_id = s.workflow_id AND d.step_id = s.step_id AND p.status <> 'COMPLETED'))
                        FROM active_steps s CROSS JOIN observed GROUP BY observed.now
                        """)) {
                    double ready = 0, age = 0, promotion = 0;
                    if (rows.next()) { ready = rows.getDouble(1); age = rows.getDouble(2); promotion = rows.getDouble(3); }
                    try (var expired = connection.createStatement(); var leases = expired.executeQuery("""
                            SELECT count(*), coalesce(extract(epoch FROM statement_timestamp() - min(lease_expires_at)), 0)
                            FROM step_execution WHERE status = 'RUNNING' AND lease_expires_at <= statement_timestamp()
                            """)) {
                        leases.next();
                        Snapshot next = new Snapshot(ready, age, promotion, leases.getDouble(1), leases.getDouble(2));
                        connection.commit();
                        snapshot = next;
                    }
                }
            }
            lastSuccess = System.currentTimeMillis();
            latestSuccess = true;
        } catch (Exception e) {
            latestSuccess = false;
            log.atWarn().addKeyValue("event", "database_sample_failed")
                    .log("Database observations unavailable; retaining the last snapshot", e);
        }
        telemetry.operation("database_sample", started, latestSuccess ? "success" : "error");
    }

    public Snapshot snapshot() { return snapshot; }
    public boolean latestSuccess() { return latestSuccess; }

    @Override
    public void start() {
        running = true;
        executor.scheduleWithFixedDelay(this::sample, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdownNow();
        pool.close();
    }

    @Override
    public boolean isRunning() { return running; }
}
