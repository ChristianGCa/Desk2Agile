package com.chris.glpi_taiga_integration.service;

import com.chris.glpi_taiga_integration.dto.GlpiItem;
import com.chris.glpi_taiga_integration.dto.GlpiPluginFieldsRecord;
import com.chris.glpi_taiga_integration.dto.GlpiTeamMember;
import com.chris.glpi_taiga_integration.dto.GlpiWebhookPayload;
import com.chris.glpi_taiga_integration.dto.TaigaWebhookPayload;
import com.chris.glpi_taiga_integration.exception.IntegrationAuthenticationException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);
    private static final String ID_TAIGA_NULL = "0";
    private static final String CONFIG_WILDCARD = "*";
    private static final int LOCK_STRIPES = 64;

    private final TaigaIntegrationService taigaIntegrationService;
    private final GlpiIntegrationService glpiIntegrationService;
    private final ProjectRoutingService projectRoutingService;

    private final Object[] locks = new Object[LOCK_STRIPES];

    @Value("${glpi.api.category-that-send-to-taiga:}")
    private String categoryThatSendToTaiga;

    @Value("${glpi.api.assignee-that-send-to-taiga:}")
    private String assigneeThatSendToTaiga;

    public IntegrationService(
            TaigaIntegrationService taigaIntegrationService,
            GlpiIntegrationService glpiIntegrationService,
            ProjectRoutingService projectRoutingService) {
        this.taigaIntegrationService = taigaIntegrationService;
        this.glpiIntegrationService = glpiIntegrationService;
        this.projectRoutingService = projectRoutingService;

        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    public void processGlpiWebhook(GlpiWebhookPayload payload) {
        var item = payload.item();
        if (!shouldSendTicketToTaiga(item)) {
            String category = (item.category() != null && item.category().name() != null)
                    ? item.category().name()
                    : "";
            String assigneeCfg = formatAssigneeTriggerForLog();
            String categoryCfg = formatCategoryTriggerForLog();
            log.debug(
                    "GLPI - Ignorado: categoria='{}' (gatilho categoria={}); técnico gatilho={} sem match em assigned.",
                    category,
                    categoryCfg,
                    assigneeCfg);
            return;
        }

        Long ticketId = item.id();
        Object lock = locks[Math.abs(ticketId.hashCode() % LOCK_STRIPES)];

        synchronized (lock) {
            /*
             * Tentativa 1. Se o interceptor detectar 401/403 e lançar
             * IntegrationAuthenticationException, os caches já foram limpos;
             * a Tentativa 2 busca tokens novos e re-executa a operação completa.
             */
            try {
                doProcessGlpiWebhook(item, ticketId);
            } catch (IntegrationAuthenticationException e) {
                log.warn("GLPI - Auth falhou na 1ª tentativa para ticket {}. Re-tentando com novas credenciais. Motivo: {}",
                        ticketId, e.getMessage());
                doProcessGlpiWebhook(item, ticketId);
            }
        }
    }

    /**
     * Envia ao Taiga se o gatilho de categoria casar ({@code *} = qualquer
     * categoria; vazio = desligado) ou se o gatilho de técnico casar ({@code *}
     * = qualquer; login específico em {@code assigned}; vazio = desligado).
     */
    private boolean shouldSendTicketToTaiga(GlpiItem item) {
        if (categoryMatches(item)) {
            return true;
        }
        return assigneeMatches(item);
    }

    private boolean categoryMatches(GlpiItem item) {
        String cfg = categoryThatSendToTaiga == null ? "" : categoryThatSendToTaiga.trim();
        if (cfg.isEmpty()) {
            return false;
        }
        if (CONFIG_WILDCARD.equals(cfg)) {
            return true;
        }
        if (item.category() == null || item.category().name() == null) {
            return false;
        }
        String category = item.category().name();
        return !category.isBlank() && category.equalsIgnoreCase(cfg);
    }

    private boolean assigneeMatches(GlpiItem item) {
        String wanted = assigneeThatSendToTaiga == null ? "" : assigneeThatSendToTaiga.trim();
        if (wanted.isEmpty()) {
            return false;
        }
        if (CONFIG_WILDCARD.equals(wanted)) {
            return true;
        }
        List<GlpiTeamMember> team = item.team();
        for (GlpiTeamMember member : team) {
            if (member == null || member.role() == null || !"assigned".equalsIgnoreCase(member.role().trim())) {
                continue;
            }
            if (member.name() != null && wanted.equalsIgnoreCase(member.name().trim())) {
                return true;
            }
            if (member.displayName() != null && wanted.equalsIgnoreCase(member.displayName().trim())) {
                return true;
            }
            if (member.realName() != null && wanted.equalsIgnoreCase(member.realName().trim())) {
                return true;
            }
            if (member.firstName() != null && wanted.equalsIgnoreCase(member.firstName().trim())) {
                return true;
            }
        }
        return false;
    }

    private String formatCategoryTriggerForLog() {
        if (categoryThatSendToTaiga == null || categoryThatSendToTaiga.isBlank()) {
            return "(desativado)";
        }
        return "'" + categoryThatSendToTaiga.trim() + "'";
    }

    private String formatAssigneeTriggerForLog() {
        if (assigneeThatSendToTaiga == null || assigneeThatSendToTaiga.isBlank()) {
            return "(desativado)";
        }
        return "'" + assigneeThatSendToTaiga.trim() + "'";
    }

    private void doProcessGlpiWebhook(GlpiItem item, Long ticketId) {
        // initSession() busca do cache (ou renova se cache foi limpo pelo interceptor).
        String sessionToken = glpiIntegrationService.initSession();

        Optional<GlpiPluginFieldsRecord> record
                = glpiIntegrationService.getPluginFieldsRecord(ticketId, sessionToken);

        if (record.isPresent()) {
            String idTaiga = record.get().taigaIdValue();
            if (idTaiga != null && !idTaiga.isBlank() && !idTaiga.equals(ID_TAIGA_NULL)) {
                log.info("GLPI - Ticket {} já possui Issue no Taiga ({}).", ticketId, idTaiga);
                return;
            }
        }

        // authenticateInTaiga() busca do cache (ou renova se cache foi limpo).
        String taigaToken = taigaIntegrationService.authenticateInTaiga();
        String targetProjectSlug = projectRoutingService.resolveTaigaProjectSlugForTicket(ticketId, sessionToken);
        var project = taigaIntegrationService.getProjectBySlug(targetProjectSlug, taigaToken);

        var taigaResponse = taigaIntegrationService.createIssueOnTaiga(
                project.id(),
                item.name(),
                item.content(),
                taigaToken);

        String taigaIssueUrl = taigaIntegrationService.buildTaigaIssueUrl(targetProjectSlug, taigaResponse.ref());
        glpiIntegrationService.updateGlpiTicket(ticketId, taigaResponse.id(), taigaIssueUrl, sessionToken);
        // dataPrevista não disponível neste fluxo; passa null.
        glpiIntegrationService.syncExternalProgress(
                ticketId,
                glpiIntegrationService.getInitialStatusExternalProgress(),
                null,
                sessionToken);

        log.info("GLPI - Integração concluída para Ticket {}: Taiga ID {}", ticketId, taigaResponse.id());
    }

    public void processTaigaWebhook(TaigaWebhookPayload payload) {
        if ("create".equals(payload.action()) || !"issue".equals(payload.type())) {
            return;
        }

        var issue = payload.data();
        if (issue.id() == null || issue.status() == null) {
            log.warn("TAIGA - Payload inválido recebido.");
            return;
        }

        try {
            doProcessTaigaWebhook(issue);
        } catch (IntegrationAuthenticationException e) {
            log.warn("TAIGA - Auth falhou na 1ª tentativa para issue {}. Re-tentando com novas credenciais. Motivo: {}",
                    issue.id(), e.getMessage());
            doProcessTaigaWebhook(issue);
        }
    }

    private void doProcessTaigaWebhook(com.chris.glpi_taiga_integration.dto.TaigaIssueData issue) {
        String sessionToken = glpiIntegrationService.initSession();
        String taigaToken = taigaIntegrationService.authenticateInTaiga();

        Optional<Long> glpiTicketId = glpiIntegrationService.getTicketByIdTaiga(issue.id(), sessionToken);
        if (glpiTicketId.isEmpty()) {
            log.warn("TAIGA - Nenhum ticket GLPI encontrado para issue Taiga {}.", issue.id());
            return;
        }

        Long ticketId = glpiTicketId.get();
        String statusNome = issue.status().name();

        var issueDetails = taigaIntegrationService.getIssueDetails(issue.id(), taigaToken);
        var project = taigaIntegrationService.getProjectById(issueDetails.projectId(), taigaToken);
        String taigaIssueUrl = taigaIntegrationService.buildTaigaIssueUrl(project.slug(), issue.ref());

        Optional<GlpiPluginFieldsRecord> record
                = glpiIntegrationService.getPluginFieldsRecord(ticketId, sessionToken);

        if (record.isPresent()) {
            glpiIntegrationService.updatePluginFields(
                    ticketId, record.get().id(), issue.id(), taigaIssueUrl, sessionToken);
        } else {
            glpiIntegrationService.createPluginFields(
                    ticketId, issue.id(), taigaIssueUrl, sessionToken);
        }

        // O GLPI espera o formato YYYY-MM-DD. O Taiga costuma enviar ISO 8601 (com 'T' e timezone).
        String dataPrevista = issueDetails.dueDate() != null ? issueDetails.dueDate().split("T")[0] : null;

        glpiIntegrationService.syncExternalProgress(ticketId, statusNome, dataPrevista, sessionToken);
        log.info("TAIGA - Status do Ticket {} atualizado para '{}' e data atualizada para {}.", ticketId, statusNome, dataPrevista);
    }
}
