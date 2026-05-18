package com.chris.glpi_taiga_integration.service;

import com.chris.glpi_taiga_integration.dto.GlpiItem;
import com.chris.glpi_taiga_integration.dto.GlpiPluginFieldsRecord;
import com.chris.glpi_taiga_integration.dto.GlpiTeamMember;
import com.chris.glpi_taiga_integration.dto.GlpiWebhookPayload;
import com.chris.glpi_taiga_integration.dto.TaigaIssueData;
import com.chris.glpi_taiga_integration.dto.TaigaPromotedToChange;
import com.chris.glpi_taiga_integration.dto.TaigaUserStoryDetailsResponse;
import com.chris.glpi_taiga_integration.dto.TaigaUserStoryStatusResponse;
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

    // -------------------------------------------------------------------------
    // GLPI webhook
    // -------------------------------------------------------------------------

    public void processGlpiWebhook(GlpiWebhookPayload payload) {
        var item = payload.item();
        if (!shouldSendTicketToTaiga(item)) {
            String category = (item.category() != null && item.category().name() != null)
                    ? item.category().name()
                    : "";
            log.debug(
                    "GLPI - Ignorado: categoria='{}' (gatilho categoria={}); técnico gatilho={} sem match em assigned.",
                    category,
                    formatCategoryTriggerForLog(),
                    formatAssigneeTriggerForLog());
            return;
        }

        Long ticketId = item.id();
        Object lock = locks[Math.abs(ticketId.hashCode() % LOCK_STRIPES)];

        synchronized (lock) {
            try {
                doProcessGlpiWebhook(item, ticketId);
            } catch (IntegrationAuthenticationException e) {
                log.warn("GLPI - Auth falhou na 1ª tentativa para ticket {}. Re-tentando. Motivo: {}",
                        ticketId, e.getMessage());
                doProcessGlpiWebhook(item, ticketId);
            }
        }
    }

    private boolean shouldSendTicketToTaiga(GlpiItem item) {
        return categoryMatches(item) || assigneeMatches(item);
    }

    private boolean categoryMatches(GlpiItem item) {
        String cfg = categoryThatSendToTaiga == null ? "" : categoryThatSendToTaiga.trim();
        if (cfg.isEmpty()) return false;
        if (CONFIG_WILDCARD.equals(cfg)) return true;
        if (item.category() == null || item.category().name() == null) return false;
        String category = item.category().name();
        return !category.isBlank() && category.equalsIgnoreCase(cfg);
    }

    private boolean assigneeMatches(GlpiItem item) {
        String wanted = assigneeThatSendToTaiga == null ? "" : assigneeThatSendToTaiga.trim();
        if (wanted.isEmpty()) return false;
        if (CONFIG_WILDCARD.equals(wanted)) return true;
        List<GlpiTeamMember> team = item.team();
        for (GlpiTeamMember member : team) {
            if (member == null || member.role() == null
                    || !"assigned".equalsIgnoreCase(member.role().trim())) continue;
            if (member.name() != null && wanted.equalsIgnoreCase(member.name().trim())) return true;
            if (member.displayName() != null && wanted.equalsIgnoreCase(member.displayName().trim())) return true;
            if (member.realName() != null && wanted.equalsIgnoreCase(member.realName().trim())) return true;
            if (member.firstName() != null && wanted.equalsIgnoreCase(member.firstName().trim())) return true;
        }
        return false;
    }

    private String formatCategoryTriggerForLog() {
        if (categoryThatSendToTaiga == null || categoryThatSendToTaiga.isBlank()) return "(desativado)";
        return "'" + categoryThatSendToTaiga.trim() + "'";
    }

    private String formatAssigneeTriggerForLog() {
        if (assigneeThatSendToTaiga == null || assigneeThatSendToTaiga.isBlank()) return "(desativado)";
        return "'" + assigneeThatSendToTaiga.trim() + "'";
    }

    private void doProcessGlpiWebhook(GlpiItem item, Long ticketId) {
        String sessionToken = glpiIntegrationService.initSession();

        Optional<GlpiPluginFieldsRecord> record =
                glpiIntegrationService.getPluginFieldsRecord(ticketId, sessionToken);

        if (record.isPresent()) {
            String idTaiga = record.get().taigaIdValue();
            if (idTaiga != null && !idTaiga.isBlank() && !idTaiga.equals(ID_TAIGA_NULL)) {
                log.info("GLPI - Ticket {} já possui Issue no Taiga ({}).", ticketId, idTaiga);
                return;
            }
        }

        String taigaToken = taigaIntegrationService.authenticateInTaiga();
        String targetProjectSlug = projectRoutingService.resolveTaigaProjectSlugForTicket(ticketId, sessionToken);
        var project = taigaIntegrationService.getProjectBySlug(targetProjectSlug, taigaToken);

        var taigaResponse = taigaIntegrationService.createIssueOnTaiga(
                project.id(), item.name(), item.content(), taigaToken);

        String taigaIssueUrl = taigaIntegrationService.buildTaigaIssueUrl(targetProjectSlug, taigaResponse.ref());
        glpiIntegrationService.updateGlpiTicket(ticketId, taigaResponse.id(), taigaIssueUrl, sessionToken);
        glpiIntegrationService.syncExternalProgress(
                ticketId,
                glpiIntegrationService.getInitialStatusExternalProgress(),
                null,
                sessionToken);

        log.info("GLPI - Integração concluída para Ticket {}: Taiga ID {}", ticketId, taigaResponse.id());
    }

    // -------------------------------------------------------------------------
    // Taiga webhook
    // -------------------------------------------------------------------------

    public void processTaigaWebhook(TaigaWebhookPayload payload) {
        if ("create".equals(payload.action()) && "issue".equals(payload.type())) {
            return;
        }

        TaigaIssueData data = payload.data();
        if (data == null) {
            log.warn("TAIGA - Payload inválido recebido.");
            return;
        }

        if ("issue".equals(payload.type())) {
            processTaigaIssueEvent(payload, data);
        } else if ("userstory".equals(payload.type())) {
            processTaigaUserStoryEvent(data);
        }
    }

    // -- issue events ---------------------------------------------------------

    private void processTaigaIssueEvent(TaigaWebhookPayload payload, TaigaIssueData data) {
        if (isPromotionEvent(payload)) {
            Long newUserStoryId = extractNewUserStoryId(payload);
            if (newUserStoryId == null) {
                log.warn("TAIGA - Promoção detectada para issue {} mas nenhum novo ID de história encontrado.",
                        data.id());
                return;
            }
            log.info("TAIGA - Issue {} promovida para história {}.", data.id(), newUserStoryId);
            try {
                doHandleIssuePromotion(data.id(), newUserStoryId);
            } catch (IntegrationAuthenticationException e) {
                log.warn("TAIGA - Auth falhou ao processar promoção da issue {}. Re-tentando. Motivo: {}",
                        data.id(), e.getMessage());
                doHandleIssuePromotion(data.id(), newUserStoryId);
            }
        } else {
            if (data.id() == null || data.status() == null) {
                log.warn("TAIGA - Payload de issue inválido recebido.");
                return;
            }
            try {
                doProcessTaigaIssueUpdate(data);
            } catch (IntegrationAuthenticationException e) {
                log.warn("TAIGA - Auth falhou para issue {}. Re-tentando. Motivo: {}", data.id(), e.getMessage());
                doProcessTaigaIssueUpdate(data);
            }
        }
    }

    private boolean isPromotionEvent(TaigaWebhookPayload payload) {
        return payload.change() != null
                && payload.change().diff() != null
                && payload.change().diff().promotedTo() != null;
    }

    private Long extractNewUserStoryId(TaigaWebhookPayload payload) {
        TaigaPromotedToChange promotedTo = payload.change().diff().promotedTo();
        List<Long> from = promotedTo.from() != null ? promotedTo.from() : List.of();
        List<Long> to = promotedTo.to() != null ? promotedTo.to() : List.of();
        return to.stream()
                .filter(id -> !from.contains(id))
                .findFirst()
                .orElse(null);
    }

    private void doHandleIssuePromotion(Long issueId, Long userStoryId) {
        String sessionToken = glpiIntegrationService.initSession();
        String taigaToken = taigaIntegrationService.authenticateInTaiga();

        Optional<Long> glpiTicketId = glpiIntegrationService.getTicketByIdTaiga(issueId, sessionToken);
        if (glpiTicketId.isEmpty()) {
            log.warn("TAIGA - Nenhum ticket GLPI encontrado para issue {} ao processar promoção.", issueId);
            return;
        }

        Long ticketId = glpiTicketId.get();
        TaigaUserStoryDetailsResponse us = taigaIntegrationService.getUserStoryDetails(userStoryId, taigaToken);

        if (us == null || us.statusId() == null) {
            log.warn("TAIGA - Detalhes da história {} não disponíveis.", userStoryId);
            return;
        }

        var statusResponse = taigaIntegrationService.getUserStoryStatus(us.statusId(), taigaToken);
        String statusNome = statusResponse != null ? statusResponse.name() : String.valueOf(us.statusId());
        String dataPrevista = us.dueDate() != null ? us.dueDate().split("T")[0] : null;

        glpiIntegrationService.syncExternalProgress(ticketId, statusNome, dataPrevista, sessionToken);
        log.info("TAIGA - Ticket {} atualizado via promoção: história={}, status='{}', dataPrevista={}.",
                ticketId, userStoryId, statusNome, dataPrevista);
    }

    private void doProcessTaigaIssueUpdate(TaigaIssueData issue) {
        // Se a issue já foi promovida para história de usuário,
        // ignora atualizações da Issue para não sobrescrever dados da história.
        if (issue.promotedTo() != null && !issue.promotedTo().isEmpty()) {
            log.info("TAIGA - Issue {} já promovida para história(s) {}. Atualização ignorada.",
                    issue.id(), issue.promotedTo());
            return;
        }

        String sessionToken = glpiIntegrationService.initSession();

        Optional<Long> glpiTicketId = glpiIntegrationService.getTicketByIdTaiga(issue.id(), sessionToken);
        if (glpiTicketId.isEmpty()) {
            log.warn("TAIGA - Nenhum ticket GLPI encontrado para issue {}.", issue.id());
            return;
        }

        Long ticketId = glpiTicketId.get();
        String statusNome = issue.status().name();

        // URL já está gravada no bloco privado "Taiga" do GLPI — sem chamada extra ao Taiga.
        Optional<GlpiPluginFieldsRecord> record =
                glpiIntegrationService.getPluginFieldsRecord(ticketId, sessionToken);

        String taigaIssueUrl = record.map(GlpiPluginFieldsRecord::taigaLinkValue).orElse(null);
        if (taigaIssueUrl == null || taigaIssueUrl.isBlank()) {
            // Fallback: reconstrói via API do Taiga se o campo não estiver gravado.
            log.warn("TAIGA - Link não encontrado no GLPI para ticket {}. Reconstruindo via API.", ticketId);
            String taigaToken = taigaIntegrationService.authenticateInTaiga();
            var issueDetails = taigaIntegrationService.getIssueDetails(issue.id(), taigaToken);
            var project = taigaIntegrationService.getProjectById(issueDetails.projectId(), taigaToken);
            taigaIssueUrl = taigaIntegrationService.buildTaigaIssueUrl(project.slug(), issue.ref());
        }

        if (record.isPresent()) {
            glpiIntegrationService.updatePluginFields(
                    ticketId, record.get().id(), issue.id(), taigaIssueUrl, sessionToken);
        } else {
            glpiIntegrationService.createPluginFields(
                    ticketId, issue.id(), taigaIssueUrl, sessionToken);
        }

        String dataPrevista = issue.dueDate() != null ? issue.dueDate().split("T")[0] : null;

        glpiIntegrationService.syncExternalProgress(ticketId, statusNome, dataPrevista, sessionToken);
        log.info("TAIGA - Ticket {} atualizado via issue: status='{}', dataPrevista={}.",
                ticketId, statusNome, dataPrevista);
    }

    // -- userstory events -----------------------------------------------------

    private void processTaigaUserStoryEvent(TaigaIssueData data) {
        if (data.id() == null || data.generatedFromIssue() == null) {
            log.info("TAIGA - História {} sem issue de origem, ignorando.", data.id());
            return;
        }
        if (data.status() == null) {
            log.warn("TAIGA - Payload de história inválido (status ausente).");
            return;
        }
        try {
            doProcessUserStoryUpdate(data);
        } catch (IntegrationAuthenticationException e) {
            log.warn("TAIGA - Auth falhou para história {}. Re-tentando. Motivo: {}", data.id(), e.getMessage());
            doProcessUserStoryUpdate(data);
        }
    }

    private void doProcessUserStoryUpdate(TaigaIssueData us) {
        String sessionToken = glpiIntegrationService.initSession();

        // Localiza o ticket pelo ID da issue original que gerou esta história.
        Optional<Long> glpiTicketId =
                glpiIntegrationService.getTicketByIdTaiga(us.generatedFromIssue(), sessionToken);
        if (glpiTicketId.isEmpty()) {
            log.warn("TAIGA - Nenhum ticket GLPI para issue {} (origem da história {}).",
                    us.generatedFromIssue(), us.id());
            return;
        }

        Long ticketId = glpiTicketId.get();
        String statusNome = us.status().name();
        String dataPrevista = us.dueDate() != null ? us.dueDate().split("T")[0] : null;

        glpiIntegrationService.syncExternalProgress(ticketId, statusNome, dataPrevista, sessionToken);
        log.info("TAIGA - Ticket {} atualizado via história {}: status='{}', dataPrevista={}.",
                ticketId, us.id(), statusNome, dataPrevista);
    }
}