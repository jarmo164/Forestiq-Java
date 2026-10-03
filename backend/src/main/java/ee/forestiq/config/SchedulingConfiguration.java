package ee.forestiq.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling is never enabled in the HTTP-only API role. */
@Configuration
@EnableScheduling
@ConditionalOnExpression("${forestiq.scheduling.enabled:true} && '${forestiq.runtime.role:all}' != 'api'")
public class SchedulingConfiguration {
}
