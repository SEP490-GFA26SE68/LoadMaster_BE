package fu.se184491.loadmaster_be.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables Spring's {@code @Async} support and configures the backing thread pool
 * used for optimization job execution (S3-08).
 *
 * <p>Thread pool sizing:
 * <ul>
 *   <li>Core threads: 2  — always alive, handle steady-state optimization load</li>
 *   <li>Max threads:  5  — burst capacity for peak concurrent job submissions</li>
 *   <li>Queue:        50 — pending jobs buffered before rejection</li>
 * </ul>
 *
 * <p>Thread name prefix {@code opt-job-} makes log tracing easy.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    public static final int CORE_POOL_SIZE = 2;
    public static final int MAX_POOL_SIZE  = 5;
    public static final int QUEUE_CAPACITY = 50;
    public static final String THREAD_NAME_PREFIX = "opt-job-";

    /**
     * The executor bean named {@code "optimizationExecutor"}.
     * Used by {@link fu.se184491.loadmaster_be.service.optimize.AsyncOptimizationRunner}
     * via {@code @Async("optimizationExecutor")}.
     */
    @Bean(name = "optimizationExecutor")
    public Executor optimizationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Default executor used for any other {@code @Async} method in the application.
     */
    @Override
    public Executor getAsyncExecutor() {
        return optimizationExecutor();
    }
}
