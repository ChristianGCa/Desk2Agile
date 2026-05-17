package com.chris.glpi_taiga_integration.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(1)
@ConfigurationProperties(prefix = "security.webhook")
public class IpAllowlistFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IpAllowlistFilter.class);

    private static final String ALLOWLIST_WILDCARD = "*";

    private List<String> allowedIps;

    /**
     * Quando {@code true}, o IP do cliente é lido do header {@code X-Forwarded-For}
     * (primeiro valor da lista, que representa o cliente original).
     *
     * <p>Configuração: {@code security.webhook.trust-proxy=true} no application.yaml.
     */
    private boolean trustProxy = false;

    public void setAllowedIps(List<String> allowedIps) {
        this.allowedIps = allowedIps;
    }

    public void setTrustProxy(boolean trustProxy) {
        this.trustProxy = trustProxy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String requestUri = request.getRequestURI();

        if (!requestUri.startsWith("/api/webhook/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);

        // Lista vazia ou entrada "*" = sem restrição de IP
        if (allowsAnyIp(allowedIps)) {
            log.debug("Allowlist aberta (vazia ou '*') — IP={} rota={}", clientIp, requestUri);
            filterChain.doFilter(request, response);
            return;
        }

        if (!allowedIps.contains(clientIp)) {
            log.warn("Acesso negado: IP={} tentou acessar rota={}", clientIp, requestUri);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acesso negado.");
            return;
        }

        log.debug("Acesso permitido: IP={} rota={}", clientIp, requestUri);
        filterChain.doFilter(request, response);
    }

    /**
     * {@code true} quando não há allowlist ou quando algum valor (após trim) é {@code "*"}.
     */
    private boolean allowsAnyIp(List<String> ips) {
        if (ips == null || ips.isEmpty()) {
            return true;
        }
        return ips.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(ALLOWLIST_WILDCARD::equals);
    }

    /**
     * Resolve o IP real do cliente.
     *
     * <p>Se {@code trustProxy=true}, lê o header {@code X-Forwarded-For} e extrai
     * o primeiro IP da cadeia (o cliente original). Formato do header:
     * {@code X-Forwarded-For: <client>, <proxy1>, <proxy2>}.
     *
     * <p>Se o header estiver ausente, em branco, ou {@code trustProxy=false},
     * usa {@code getRemoteAddr()} como fallback.
     */
    private String resolveClientIp(HttpServletRequest request) {
        if (trustProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // Pega o primeiro IP da lista (o cliente original).
                String firstIp = xff.split(",")[0].trim();
                if (!firstIp.isEmpty()) {
                    return firstIp;
                }
            }
        }
        return request.getRemoteAddr();
    }
}