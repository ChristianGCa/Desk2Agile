package com.chris.glpi_taiga_integration.controller;

import com.chris.glpi_taiga_integration.config.WebhookSecurityProperties;
import com.chris.glpi_taiga_integration.dto.GlpiWebhookPayload;
import com.chris.glpi_taiga_integration.dto.TaigaWebhookPayload;
import com.chris.glpi_taiga_integration.exception.GlpiPluginFieldsException;
import com.chris.glpi_taiga_integration.service.IntegrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Controller responsável por receber webhooks do GLPI e Taiga.
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final String HMAC_ALGO = "HmacSHA1";

    private final IntegrationService integrationService;
    private final com.chris.glpi_taiga_integration.service.FailureLogService failureLogService;
    private final WebhookSecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    public WebhookController(IntegrationService integrationService,
                             com.chris.glpi_taiga_integration.service.FailureLogService failureLogService,
                             WebhookSecurityProperties securityProperties,
                             ObjectMapper objectMapper) {
        this.integrationService = integrationService;
        this.failureLogService = failureLogService;
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Recebe webhooks enviados pelo GLPI.
     * Valida o header {@code Authorization: Bearer <token>}.
     */
    @PostMapping(value = "/glpi", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public ResponseEntity<String> receiveWebhookGlpi(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody GlpiWebhookPayload payload) {

        log.debug("ROTA /glpi - Payload recebido");

        if (!isGlpiTokenValid(authHeader)) {
            log.warn("ROTA /glpi - Token inválido ou ausente.");
            return ResponseEntity.status(401).body("Não autorizado.");
        }

        if (payload == null || payload.item() == null) {
            log.warn("ROTA /glpi - Payload inválido: item ausente.");
            return ResponseEntity.badRequest().body("Payload inválido: item ausente.");
        }

        try {
            integrationService.processGlpiWebhook(payload);
            return ResponseEntity.ok("Webhook GLPI processado com sucesso.");
        } catch (GlpiPluginFieldsException e) {
            log.error("ROTA /glpi - Erro no Plugin Fields: {}", e.getMessage());
            failureLogService.logFailure("Webhook GLPI (Plugin Fields)", e);
            return ResponseEntity.badRequest().body("Erro no Plugin Fields do GLPI: " + e.getMessage());
        } catch (Exception e) {
            log.error("ROTA /glpi - Erro ao processar webhook", e);
            failureLogService.logFailure("Webhook GLPI", e);
            return ResponseEntity.internalServerError().body("Erro interno ao processar webhook GLPI.");
        }
    }

    /**
     * Recebe webhooks enviados pelo Taiga.
     * Valida o HMAC-SHA1 do body contra o header {@code X-Taiga-Webhook-Signature}.
     */
    @PostMapping("/taiga")
    public ResponseEntity<String> receiveWebhookTaiga(
            @RequestHeader(value = "X-Taiga-Webhook-Signature", required = false) String taigaSignature,
            @RequestBody(required = false) String rawBody) {

        log.debug("ROTA /taiga - Payload recebido");

        if (!isTaigaSignatureValid(taigaSignature, rawBody)) {
            log.warn("ROTA /taiga - Assinatura inválida ou ausente.");
            return ResponseEntity.status(401).body("Não autorizado.");
        }

        if (rawBody == null || rawBody.isBlank()) {
            log.warn("ROTA /taiga - Body ausente.");
            return ResponseEntity.badRequest().body("Payload inválido: body ausente.");
        }

        TaigaWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, TaigaWebhookPayload.class);
        } catch (Exception e) {
            log.error("ROTA /taiga - Erro ao desserializar payload", e);
            return ResponseEntity.badRequest().body("Payload inválido: JSON malformado.");
        }

        if (payload.data() == null) {
            log.warn("ROTA /taiga - Payload inválido: data ausente.");
            return ResponseEntity.badRequest().body("Payload inválido: data ausente.");
        }

        String type = payload.type();
        if (!type.equals("issue") && !type.equals("userstory")) {
            log.info("ROTA /taiga - Evento ignorado pois não é issue ou userstory: tipo '{}'", type);
            return ResponseEntity.ok("Evento ignorado: tipo '" + type + "' não tratado.");
        }

        try {
            integrationService.processTaigaWebhook(payload);
            return ResponseEntity.ok("Webhook Taiga processado com sucesso.");
        } catch (GlpiPluginFieldsException e) {
            log.error("ROTA /taiga - Erro no Plugin Fields: {}", e.getMessage());
            failureLogService.logFailure("Webhook Taiga (Plugin Fields)", e);
            return ResponseEntity.badRequest().body("Erro no Plugin Fields do GLPI: " + e.getMessage());
        } catch (Exception e) {
            log.error("ROTA /taiga - Erro ao processar webhook", e);
            failureLogService.logFailure("Webhook Taiga", e);
            return ResponseEntity.internalServerError().body("Erro interno ao processar webhook Taiga.");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers de validação
    // -------------------------------------------------------------------------

    /**
     * Valida o token Bearer do GLPI.
     * Se nenhum token estiver configurado, aceita qualquer requisição (log de aviso).
     */
    private boolean isGlpiTokenValid(String authHeader) {
        String configured = securityProperties.getGlpiToken();
        if (configured == null || configured.isBlank()) {
            log.warn("GLPI webhook token não configurado — validação desabilitada.");
            return true;
        }
        String expected = "Bearer " + configured;
        return expected.equals(authHeader);
    }

    /**
     * Valida a assinatura HMAC-SHA1 do Taiga.
     * O Taiga envia: HMAC-SHA1(secret, rawBody) em hex no header X-Taiga-Webhook-Signature.
     * Se nenhuma secret estiver configurada, aceita qualquer requisição (log de aviso).
     */
    private boolean isTaigaSignatureValid(String signature, String rawBody) {
        String secret = securityProperties.getTaigaSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("Taiga webhook secret não configurada — validação desabilitada.");
            return true;
        }
        if (signature == null || signature.isBlank() || rawBody == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] hash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String computed = HexFormat.of().formatHex(hash);
            return computed.equalsIgnoreCase(signature);
        } catch (Exception e) {
            log.error("Erro ao calcular HMAC-SHA1 do Taiga", e);
            return false;
        }
    }
}
