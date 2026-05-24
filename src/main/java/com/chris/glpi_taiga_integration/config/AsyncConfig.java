package com.chris.glpi_taiga_integration.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configura o executor assíncrono utilizado para processar webhooks fora da thread HTTP.
 * O controller responde 202 imediatamente; o processamento real ocorre neste pool.
 * Se a fila estiver cheia, o executor rejeita a tarefa e o controller retorna 503.
 * Parâmetros configuráveis via {@code application.yaml}:
 * <pre>
 * integration:
 *   async:
 *     core-pool-size: 2      # threads sempre ativas
 *     max-pool-size: 5       # limite sob pico
 *     queue-capacity: 100    # tarefas pendentes antes de rejeitar
 *     shutdown-timeout: 30   # segundos para drenar na parada
 * </pre>
 */
@Configuration
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Value("${integration.async.core-pool-size:2}")
    private int corePoolSize;

    @Value("${integration.async.max-pool-size:5}")
    private int maxPoolSize;

    @Value("${integration.async.queue-capacity:100}")
    private int queueCapacity;

    @Value("${integration.async.shutdown-timeout:30}")
    private int shutdownTimeout;

    /**
     * Bean do executor de webhooks.
     * Política de rejeição: {@link ThreadPoolExecutor.AbortPolicy} - lança
     * {@link java.util.concurrent.RejectedExecutionException}, que o controller
     * converte em HTTP 503. Alternativa: {@code CallerRunsPolicy} para processar
     * na thread HTTP (bloqueia, mas nunca descarta).
     */
    @Bean(name = "webhookExecutor")
    public ThreadPoolTaskExecutor webhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("webhook-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(shutdownTimeout);
        executor.initialize();

        log.info(
                "Webhook executor iniciado: core={}, max={}, queue={}, shutdownTimeout={}s",
                corePoolSize, maxPoolSize, queueCapacity, shutdownTimeout);

        return executor;
    }
}