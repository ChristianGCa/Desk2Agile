package com.chris.glpi_taiga_integration.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String GLPI_SESSION_CACHE = "glpiSession";
    public static final String TAIGA_TOKEN_CACHE = "taigaToken";
    public static final String TAIGA_PROJECTS_CACHE = "taigaProjects";

    @Value("${cache.glpi.session-ttl-minutes:45}")
    private long glpiSessionTtlMinutes;

    @Value("${cache.taiga.token-ttl-hours:12}")
    private long taigaTokenTtlHours;

    @Value("${cache.taiga.projects-ttl-hours:12}")
    private long taigaProjectsTtlHours;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        manager.registerCustomCache(GLPI_SESSION_CACHE,
                Caffeine.newBuilder().expireAfterWrite(glpiSessionTtlMinutes, TimeUnit.MINUTES).maximumSize(1).build());

        manager.registerCustomCache(TAIGA_TOKEN_CACHE,
                Caffeine.newBuilder().expireAfterWrite(taigaTokenTtlHours, TimeUnit.HOURS).maximumSize(1).build());

        manager.registerCustomCache(TAIGA_PROJECTS_CACHE,
                Caffeine.newBuilder().expireAfterWrite(taigaProjectsTtlHours, TimeUnit.HOURS).maximumSize(100).build());

        return manager;
    }
}