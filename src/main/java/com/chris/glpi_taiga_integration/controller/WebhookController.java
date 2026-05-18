package com.chris.glpi_taiga_integration.controller;

import com.chris.glpi_taiga_integration.dto.GlpiWebhookPayload;
import com.chris.glpi_taiga_integration.dto.TaigaWebhookPayload;
import com.chris.glpi_taiga_integration.exception.GlpiPluginFieldsException;
import com.chris.glpi_taiga_integration.service.IntegrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private final IntegrationService integrationService;

    public WebhookController(IntegrationService integrationService) {
        this.integrationService = integrationService;
    }

    @PostMapping(value = "/glpi", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public ResponseEntity<String> receiveWebhookGlpi(@RequestBody GlpiWebhookPayload payload) {
        log.info("ROTA /glpi - Payload recebido");

        if (payload == null || payload.item() == null) {
            log.warn("ROTA /glpi - Payload inválido: item ausente.");
            return ResponseEntity.badRequest().body("Payload inválido: item ausente.");
        }

        try {
            integrationService.processGlpiWebhook(payload);
            return ResponseEntity.ok("Webhook GLPI processado com sucesso.");
        } catch (GlpiPluginFieldsException e) {
            log.error("ROTA /glpi - Erro no Plugin Fields: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Erro no Plugin Fields do GLPI: " + e.getMessage());
        } catch (Exception e) {
            log.error("ROTA /glpi - Erro ao processar webhook", e);
            return ResponseEntity.internalServerError().body("Erro interno ao processar webhook GLPI.");
        }
    }

    @PostMapping("/taiga")
    public ResponseEntity<String> receiveWebhookTaiga(@RequestBody(required = false) TaigaWebhookPayload payload) {
        log.info("ROTA /taiga - Payload recebido");

        if (payload == null || payload.data() == null) {
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
            return ResponseEntity.badRequest().body("Erro no Plugin Fields do GLPI: " + e.getMessage());
        } catch (Exception e) {
            log.error("ROTA /taiga - Erro ao processar webhook", e);
            return ResponseEntity.internalServerError().body("Erro interno ao processar webhook Taiga.");
        }
    }
}
