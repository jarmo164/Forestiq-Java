package ee.forestiq.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
class IntegrationTaskConfiguration {
    @Bean("integrationTaskExecutor")
    ThreadPoolTaskExecutor integrationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("integration-job-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated bounded executor for the worker runtime. Queue capacity is zero
     * deliberately: PostgreSQL is the durable queue, so claimed runs are never
     * hidden inside an in-memory executor backlog.
     */
    @Bean("integrationWorkerTaskExecutor")
    @ConditionalOnExpression("'${forestiq.runtime.role:all}' == 'worker'")
    ThreadPoolTaskExecutor integrationWorkerTaskExecutor(
        @Value("${forestiq.runtime.worker-concurrency:4}") int configuredConcurrency) {
        int concurrency = Math.max(1, Math.min(configuredConcurrency, 32));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("integration-worker-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }
}
