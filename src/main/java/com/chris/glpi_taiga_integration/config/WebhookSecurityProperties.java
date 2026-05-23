package com.chris.glpi_taiga_integration.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Propriedades de segurança dos webhooks recebidos.
 * GLPI: valida o header {@code Authorization: Bearer <token>}.
 * Taiga: valida o HMAC-SHA1 do body cru contra o header
 * {@code X-Taiga-Webhook-Signature}.
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "security.webhook")
public class WebhookSecurityProperties {

    /**
     * Token Bearer esperado no header {@code Authorization} dos webhooks do GLPI.
     * Deixe em branco para desabilitar a validação (não recomendado).
     */
    private String glpiToken;

    /**
     * Secret key configurada nos webhooks do Taiga.
     * Usada para verificar o HMAC-SHA1 do body.
     * Deixe em branco para desabilitar a validação (não recomendado).
     */
    private String taigaSecret;

}
