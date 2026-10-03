package ee.forestiq.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PerformanceMetricsConfiguration {

    @Bean
    MeterFilter forestIqPerformancePercentiles() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!"http.server.requests".equals(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                    .percentiles(0.95, 0.99)
                    .percentilesHistogram(true)
                    .build()
                    .merge(config);
            }
        };
    }
}
