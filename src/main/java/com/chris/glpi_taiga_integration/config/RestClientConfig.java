package com.chris.glpi_taiga_integration.config;

import com.chris.glpi_taiga_integration.exception.IntegrationAuthenticationException;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Quando true, desabilita a verificação de certificado SSL.
     * Configurável via variável de ambiente SSL_SKIP_VERIFY=true.
     * ATENÇÃO: use apenas em ambientes controlados (dev/homologação).
     */
    @Value("${ssl.skip-verify:false}")
    private boolean skipVerify;

    @Bean
    public RestClient restClient(CacheManager cacheManager)
            throws NoSuchAlgorithmException, KeyManagementException {

        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5));

        if (skipVerify) {
            log.warn("SSL_SKIP_VERIFY=true — verificação de certificado DESABILITADA. "
                    + "Não use em produção com dados sensíveis.");
            builder.sslContext(buildTrustAllSslContext());
        }

        HttpClient httpClient = builder.build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(15));

        return RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor(authInvalidationInterceptor(cacheManager))
                .build();
    }

    /**
     * SSLContext que aceita qualquer certificado — autoassinado, expirado, etc.
     * Alternativa segura: monte seu certificado em /app/certs/ e o entrypoint
     * o importa automaticamente no truststore da JVM.
     */
    private SSLContext buildTrustAllSslContext()
            throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new java.security.SecureRandom());
        return ctx;
    }

    private ClientHttpRequestInterceptor authInvalidationInterceptor(CacheManager cacheManager) {
        return (request, body, execution) -> {
            var response = execution.execute(request, body);

            if (response.getStatusCode() == HttpStatus.UNAUTHORIZED
                    || response.getStatusCode() == HttpStatus.FORBIDDEN) {

                String sanitizedUri = sanitize(request.getURI());

                log.warn("REST CLIENT - {}: credenciais rejeitadas em '{}'. Caches invalidados.",
                        response.getStatusCode(), sanitizedUri);

                var glpiCache = cacheManager.getCache(CacheConfig.GLPI_SESSION_CACHE);
                var taigaCache = cacheManager.getCache(CacheConfig.TAIGA_TOKEN_CACHE);

                if (glpiCache != null) glpiCache.clear();
                if (taigaCache != null) taigaCache.clear();

                throw new IntegrationAuthenticationException(
                        "Credenciais recusadas pela API [" + response.getStatusCode()
                                + "] em: " + sanitizedUri);
            }

            return response;
        };
    }

    private String sanitize(URI uri) {
        if (uri == null) return "null";
        try {
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString();
        } catch (Exception e) {
            return "[PROTECTED URI]";
        }
    }
}