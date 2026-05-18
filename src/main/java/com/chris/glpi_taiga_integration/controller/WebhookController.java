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

/**
 * Controller responsável por receber webhooks do GLPI e Taiga.
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    /**
     * Serviço responsável pelo processamento da integração.
     */
    private final IntegrationService integrationService;

    /**
     * Serviço para log de falhas em arquivo.
     */
    private final com.chris.glpi_taiga_integration.service.FailureLogService failureLogService;

    /**
     * Injeta os serviços necessários.
     *
     * @param integrationService serviço de integração
     * @param failureLogService serviço de log de falhas
     */
    public WebhookController(IntegrationService integrationService, com.chris.glpi_taiga_integration.service.FailureLogService failureLogService) {
        this.integrationService = integrationService;
        this.failureLogService = failureLogService;
    }

    /**
     * Recebe webhooks enviados pelo GLPI.
     *
     * @param payload payload recebido do GLPI
     * @return resposta HTTP do processamento
     */
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
     *
     * @param payload payload recebido do Taiga
     * @return resposta HTTP do processamento
     */
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
            failureLogService.logFailure("Webhook Taiga (Plugin Fields)", e);
            return ResponseEntity.badRequest().body("Erro no Plugin Fields do GLPI: " + e.getMessage());
        } catch (Exception e) {
            log.error("ROTA /taiga - Erro ao processar webhook", e);
            failureLogService.logFailure("Webhook Taiga", e);
            return ResponseEntity.internalServerError().body("Erro interno ao processar webhook Taiga.");
        }
    }
}

