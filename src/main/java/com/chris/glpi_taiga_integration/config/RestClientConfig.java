package com.chris.glpi_taiga_integration.config;

import com.chris.glpi_taiga_integration.exception.IntegrationAuthenticationException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    @Bean
    public RestClient restClient(CacheManager cacheManager) {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(15));

        return RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor(authInvalidationInterceptor(cacheManager))
                .build();
    }

    /**
     * Interceptor de autenticação.
     * Executa a requisição normalmente.
     * Se receber 401 ou 403, invalida os caches de sessão GLPI e token Taiga.
     * Lança {@link IntegrationAuthenticationException} para que a camada de serviço
     * possa capturar, obter credenciais novas e re-executar a operação completa.
     */
    private ClientHttpRequestInterceptor authInvalidationInterceptor(CacheManager cacheManager) {
        return (request, body, execution) -> {
            var response = execution.execute(request, body);

            if (response.getStatusCode() == HttpStatus.UNAUTHORIZED || response.getStatusCode() == HttpStatus.FORBIDDEN) {

                String sanitizedUri = sanitize(request.getURI());

                log.warn("REST CLIENT - {}: credenciais rejeitadas em '{}'. Caches invalidados.",
                        response.getStatusCode(), sanitizedUri);

                var glpiCache = cacheManager.getCache(CacheConfig.GLPI_SESSION_CACHE);
                var taigaCache = cacheManager.getCache(CacheConfig.TAIGA_TOKEN_CACHE);

                if (glpiCache != null) glpiCache.clear();
                if (taigaCache != null) taigaCache.clear();

                // Sinaliza para a camada de serviço re-autenticar e re-executar.
                throw new IntegrationAuthenticationException(
                        "Credenciais recusadas pela API [" + response.getStatusCode()
                                + "] em: " + sanitizedUri);
            }

            return response;
        };
    }

    /**
     * Remove parâmetros de consulta (query string) e fragmentos da URI para evitar
     * logar informações sensíveis (tokens, chaves, etc).
     */
    private String sanitize(URI uri) {
        if (uri == null) {
            return "null";
        }
        try {
            return new URI(uri.getScheme(),
                    uri.getAuthority(),
                    uri.getPath(),
                    null, // query
                    null) // fragment
                    .toString();
        } catch (Exception e) {
            return "[PROTECTED URI]";
        }
    }
}