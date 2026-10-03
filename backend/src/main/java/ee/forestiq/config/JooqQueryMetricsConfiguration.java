package ee.forestiq.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteListenerProvider;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JooqQueryMetricsConfiguration {

    @Bean
    ExecuteListenerProvider forestIqQueryTimingListener(MeterRegistry meterRegistry,
                                                        @Value("${forestiq.observability.slow-query-ms:250}") long slowQueryMs) {
        return new DefaultExecuteListenerProvider(new QueryTimingListener(meterRegistry, slowQueryMs));
    }

    private static final class QueryTimingListener implements ExecuteListener {
        private static final Logger log = LoggerFactory.getLogger(QueryTimingListener.class);
        private static final String START_NANOS = QueryTimingListener.class.getName() + ".startNanos";

        private final MeterRegistry meterRegistry;
        private final long slowQueryNanos;

        private QueryTimingListener(MeterRegistry meterRegistry, long slowQueryMs) {
            this.meterRegistry = meterRegistry;
            this.slowQueryNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, slowQueryMs));
        }

        @Override
        public void executeStart(ExecuteContext context) {
            context.data(START_NANOS, System.nanoTime());
        }

        @Override
        public void executeEnd(ExecuteContext context) {
            Object started = context.data(START_NANOS);
            if (!(started instanceof Long startNanos)) return;
            long elapsed = Math.max(0L, System.nanoTime() - startNanos);
            String type = context.type() == null ? "OTHER" : context.type().name();

            Timer.builder("forestiq.db.jooq.query.latency")
                .description("jOOQ execute latency without SQL text or tenant identifiers")
                .tag("type", type)
                .publishPercentiles(0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(elapsed, TimeUnit.NANOSECONDS);

            if (elapsed >= slowQueryNanos) {
                Counter.builder("forestiq.db.jooq.query.slow")
                    .description("jOOQ executions over the configured slow-query threshold")
                    .tag("type", type)
                    .register(meterRegistry)
                    .increment();
                log.warn("Slow jOOQ query type={} durationMs={} fingerprint={}",
                    type, TimeUnit.NANOSECONDS.toMillis(elapsed), fingerprint(context.sql()));
            }
        }

        private static String fingerprint(String sql) {
            if (sql == null || sql.isBlank()) return "unknown";
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sql.replaceAll("\\s+", " ").trim().getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest, 0, 8);
            } catch (NoSuchAlgorithmException impossible) {
                return Integer.toHexString(sql.hashCode());
            }
        }
    }
}
