package com.chris.glpi_taiga_integration.service;

import com.chris.glpi_taiga_integration.dto.GlpiItem;
import com.chris.glpi_taiga_integration.dto.GlpiPluginFieldsRecord;
import com.chris.glpi_taiga_integration.dto.GlpiTeamMember;
import com.chris.glpi_taiga_integration.dto.GlpiWebhookPayload;
import com.chris.glpi_taiga_integration.dto.TaigaIssueData;
import com.chris.glpi_taiga_integration.dto.TaigaPromotedToChange;
import com.chris.glpi_taiga_integration.dto.TaigaUserStoryDetailsResponse;
import com.chris.glpi_taiga_integration.dto.TaigaWebhookPayload;
import com.chris.glpi_taiga_integration.exception.IntegrationAuthenticationException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Serviço centralizador da integração bidirecional entre GLPI e Taiga.
 * Gerencia o fluxo de criação de issues e sincronização de progresso via Webhooks.
 */
@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);
    private static final String ID_TAIGA_NULL = "0";
    private static final String CONFIG_WILDCARD = "*";
    private static final int LOCK_STRIPES = 64;

    private final TaigaIntegrationService taigaIntegrationService;
    private final GlpiIntegrationService glpiIntegrationService;
    private final ProjectRoutingService projectRoutingService;
    private final FailureLogService failureLogService;

    /** Lock listrado para mitigar concorrência local baseada no ID do ticket. */
    private final Object[] locks = new Object[LOCK_STRIPES];

    @Value("${glpi.api.category-that-send-to-taiga:}")
    private String categoryThatSendToTaiga;

    @Value("${glpi.api.assignee-that-send-to-taiga:}")
    private String assigneeThatSendToTaiga;

    @Value("${integration.auth.max-retries:1}")
    private int maxAuthRetries;

    /**
     * Construtor da classe. Inicializa as dependências e a estrutura de travas locais.
     */
    public IntegrationService(
            TaigaIntegrationService taigaIntegrationService,
            GlpiIntegrationService glpiIntegrationService,
            ProjectRoutingService projectRoutingService,
            FailureLogService failureLogService) {
        this.taigaIntegrationService = taigaIntegrationService;
        this.glpiIntegrationService = glpiIntegrationService;
        this.projectRoutingService = projectRoutingService;
        this.failureLogService = failureLogService;

        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    // GLPI Webhook

    /**
     * Processa os payloads recebidos do webhook do GLPI.
     * Filtra tickets elegíveis e aplica concorrência isolada por ID.
     *
     * @param payload Dados enviados pelo gatilho do GLPI.
     */
    public void processGlpiWebhook(GlpiWebhookPayload payload) {
        GlpiItem item = payload.item();

        if (!shouldSendTicketToTaiga(item)) {
            logIgnoredTicket(item);
            return;
        }

        Long ticketId = item.id();
        int lockIndex = (ticketId.hashCode() & Integer.MAX_VALUE) % LOCK_STRIPES;

        synchronized (locks[lockIndex]) {
            processGlpiWebhookWithRetry(item, ticketId);
        }
    }

    /**
     * Executa o fluxo de webhook do GLPI sob a política de repetição de autenticação.
     */
    private void processGlpiWebhookWithRetry(GlpiItem item, Long ticketId) {
        withAuthRetry(new Runnable() {
            @Override
            public void run() {
                doProcessGlpiWebhook(item, ticketId);
            }
        });
    }

    /**
     * Registra em log os motivos de um ticket do GLPI ter sido ignorado pela integração.
     */
    private void logIgnoredTicket(GlpiItem item) {
        String category = "";

        if (item.category() != null && item.category().name() != null) {
            category = item.category().name();
        }

        log.debug(
                "GLPI - Ignorado: categoria='{}' (gatilho categoria={}); técnico gatilho={} sem match em assigned.",
                category,
                formatCategoryTriggerForLog(),
                formatAssigneeTriggerForLog());
    }

    /**
     * Avalia se o ticket atende aos critérios mínimos de envio (categoria ou técnico atribuído).
     */
    private boolean shouldSendTicketToTaiga(GlpiItem item) {
        return categoryMatches(item) || assigneeMatches(item);
    }

    /**
     * Valida se a categoria do ticket coincide com o wildcard ou regra explícita configurada.
     */
    private boolean categoryMatches(GlpiItem item) {
        if (categoryThatSendToTaiga == null) {
            return false;
        }
        String configCategory = categoryThatSendToTaiga.trim();
        if (configCategory.isEmpty()) {
            return false;
        }
        if (CONFIG_WILDCARD.equals(configCategory)) {
            return true;
        }
        if (item.category() == null) {
            return false;
        }
        String itemCategory = item.category().name();
        if (itemCategory == null || itemCategory.isBlank()) {
            return false;
        }
        return itemCategory.equalsIgnoreCase(configCategory);
    }

    /**
     * Varre os membros atribuídos ao ticket buscando correspondência com o técnico alvo configurado.
     */
    private boolean assigneeMatches(GlpiItem item) {
        if (assigneeThatSendToTaiga == null) {
            return false;
        }

        String wantedAssignee = assigneeThatSendToTaiga.trim();

        if (wantedAssignee.isEmpty()) {
            return false;
        }

        if (CONFIG_WILDCARD.equals(wantedAssignee)) {
            return true;
        }

        List<GlpiTeamMember> team = item.team();

        for (GlpiTeamMember member : team) {
            if (!isAssignedMember(member)) {
                continue;
            }

            if (matchesAssignee(member.name(), wantedAssignee)) {
                return true;
            }

            if (matchesAssignee(member.displayName(), wantedAssignee)) {
                return true;
            }

            if (matchesAssignee(member.realName(), wantedAssignee)) {
                return true;
            }

            if (matchesAssignee(member.firstName(), wantedAssignee)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Verifica se o membro da equipe possui o papel explícito de atribuído ("assigned").
     */
    private boolean isAssignedMember(GlpiTeamMember member) {
        if (member == null) {
            return false;
        }

        if (member.role() == null) {
            return false;
        }

        return "assigned".equalsIgnoreCase(member.role().trim());
    }

    /**
     * Compara strings limpando espaços e ignorando maiúsculas/minúsculas.
     */
    private boolean matchesAssignee(String value, String wantedAssignee) {
        if (value == null) {
            return false;
        }

        return wantedAssignee.equalsIgnoreCase(value.trim());
    }

    /**
     * Formata o gatilho de categoria para exibição limpa no log.
     */
    private String formatCategoryTriggerForLog() {
        if (categoryThatSendToTaiga == null) {
            return "(desativado)";
        }

        String value = categoryThatSendToTaiga.trim();

        if (value.isBlank()) {
            return "(desativado)";
        }

        return "'" + value + "'";
    }

    /**
     * Formata o gatilho de técnico atribuído para exibição limpa no log.
     */
    private String formatAssigneeTriggerForLog() {
        if (assigneeThatSendToTaiga == null) {
            return "(desativado)";
        }

        String value = assigneeThatSendToTaiga.trim();

        if (value.isBlank()) {
            return "(desativado)";
        }

        return "'" + value + "'";
    }

    /**
     * Core do processamento do webhook GLPI. Inicializa sessão, valida duplicidade,
     * roteia o projeto, cria a issue no Taiga e atualiza os campos customizados do GLPI.
     */
    private void doProcessGlpiWebhook(GlpiItem item, Long ticketId) {
        String sessionToken = glpiIntegrationService.initSession();

        Optional<GlpiPluginFieldsRecord> record =
                glpiIntegrationService.getPluginFieldsRecord(ticketId, sessionToken);

        if (ticketAlreadyIntegrated(record, ticketId)) {
            return;
        }

        String taigaToken = taigaIntegrationService.authenticateInTaiga();

        String projectSlug =
                projectRoutingService.resolveTaigaProjectSlugForTicket(ticketId, sessionToken);

        var project =
                taigaIntegrationService.getProjectBySlug(projectSlug, taigaToken);

        var taigaIssue =
                taigaIntegrationService.createIssueOnTaiga(
                        project.id(),
                        item.name(),
                        item.content(),
                        taigaToken);

        String taigaIssueUrl =
                taigaIntegrationService.buildTaigaIssueUrl(
                        projectSlug,
                        taigaIssue.ref());

        glpiIntegrationService.updateGlpiTicket(
                ticketId,
                taigaIssue.id(),
                taigaIssueUrl,
                sessionToken);

        glpiIntegrationService.syncExternalProgress(
                ticketId,
                glpiIntegrationService.getInitialStatusExternalProgress(),
                null,
                sessionToken);

        log.info(
                "GLPI - Integração concluída para Ticket {}: Taiga ID {}",
                ticketId,
                taigaIssue.id());
    }

    /**
     * Verifica se o ticket já possui um ID externo válido atrelado no plugin de campos do GLPI.
     */
    private boolean ticketAlreadyIntegrated(
            Optional<GlpiPluginFieldsRecord> record,
            Long ticketId) {

        if (record.isEmpty()) {
            return false;
        }

        String taigaId = record.get().taigaIdValue();

        if (taigaId == null || taigaId.isBlank()) {
            return false;
        }

        if (ID_TAIGA_NULL.equals(taigaId)) {
            return false;
        }

        log.info(
                "GLPI - Ticket {} já possui Issue no Taiga ({})",
                ticketId,
                taigaId);

        return true;
    }

    // Taiga

    /**
     * Processa payloads vindos do webhook do Taiga.
     * Filtra e redireciona eventos legítimos de modificação em Issues ou User Stories.
     *
     * @param payload Evento disparado pelo Taiga.
     */
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

    /**
     * Encaminha eventos relacionados estritamente a Issues do Taiga (atualização ou promoção).
     */
    private void processTaigaIssueEvent(TaigaWebhookPayload payload, TaigaIssueData data) {

        if (isPromotionEvent(payload)) {
            processPromotionEvent(payload, data);
            return;
        }

        if (data.id() == null || data.status() == null) {
            log.warn("TAIGA - Payload de issue inválido recebido.");
            return;
        }

        withAuthRetry(new Runnable() {

            @Override
            public void run() {
                doProcessTaigaIssueUpdate(data);
            }

        });
    }

    /**
     * Trata o subevento de conversão/promoção de uma Issue para User Story dentro do Taiga.
     */
    private void processPromotionEvent(
            TaigaWebhookPayload payload,
            TaigaIssueData data) {

        Long newUserStoryId = extractNewUserStoryId(payload);

        if (newUserStoryId == null) {
            log.warn(
                    "TAIGA - Promoção detectada para issue {} mas nenhum novo ID de história encontrado.",
                    data.id());
            return;
        }

        log.info(
                "TAIGA - Issue {} promovida para história {}.",
                data.id(),
                newUserStoryId);

        withAuthRetry(new Runnable() {

            @Override
            public void run() {
                doHandleIssuePromotion(data.id(), newUserStoryId);
            }

        });
    }

    /**
     * Valida a estrutura interna do payload do Taiga para identificar mutação de promoção.
     */
    private boolean isPromotionEvent(TaigaWebhookPayload payload) {
        return payload.change() != null
                && payload.change().diff() != null
                && payload.change().diff().promotedTo() != null;
    }

    /**
     * Extrai a diferença estrutural (diff) para encontrar o ID resultante da User Story promovida.
     */
    private Long extractNewUserStoryId(TaigaWebhookPayload payload) {

        TaigaPromotedToChange promotedTo =
                payload.change().diff().promotedTo();

        List<Long> from = promotedTo.from();
        if (from == null) {
            from = List.of();
        }

        List<Long> to = promotedTo.to();
        if (to == null) {
            to = List.of();
        }

        for (Long id : to) {
            if (!from.contains(id)) {
                return id;
            }
        }

        return null;
    }

    /**
     * Executa a sincronização do GLPI quando uma Issue vira User Story, buscando o novo status e data alvo.
     */
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

        String statusNome = String.valueOf(us.statusId());

        if (statusResponse != null) {
            statusNome = statusResponse.name();
        }

        String dataPrevista = null;

        if (us.dueDate() != null) {
            dataPrevista = us.dueDate().split("T")[0];
        }

        glpiIntegrationService.syncExternalProgress(ticketId, statusNome, dataPrevista, sessionToken);
        log.info("TAIGA - Ticket {} updated via promoção: história={}, status='{}', dataPrevista={}.",
                ticketId, userStoryId, statusNome, dataPrevista);
    }

    /**
     * Atualiza o progresso externo no GLPI com base nas alterações sofridas pela Issue original no Taiga.
     */
    private void doProcessTaigaIssueUpdate(TaigaIssueData issue) {

        if (issue.promotedTo() != null && !issue.promotedTo().isEmpty()) {
            log.info(
                    "TAIGA - Issue {} já promovida para história(s) {}. Atualização ignorada.",
                    issue.id(),
                    issue.promotedTo());
            return;
        }

        String sessionToken = glpiIntegrationService.initSession();

        Optional<Long> glpiTicketId =
                glpiIntegrationService.getTicketByIdTaiga(
                        issue.id(),
                        sessionToken);

        if (glpiTicketId.isEmpty()) {
            log.warn(
                    "TAIGA - Nenhum ticket GLPI encontrado para issue {}.",
                    issue.id());
            return;
        }

        Long ticketId = glpiTicketId.get();
        String statusNome = issue.status().name();

        Optional<GlpiPluginFieldsRecord> record =
                glpiIntegrationService.getPluginFieldsRecord(
                        ticketId,
                        sessionToken);

        String taigaIssueUrl = null;

        if (record.isPresent()) {
            taigaIssueUrl = record.get().taigaLinkValue();
        }
        if (taigaIssueUrl == null || taigaIssueUrl.isBlank()) {
            log.warn(
                    "TAIGA - Link não encontrado no GLPI para ticket {}. Reconstruindo via API.",
                    ticketId);
            String taigaToken =
                    taigaIntegrationService.authenticateInTaiga();
            var issueDetails =
                    taigaIntegrationService.getIssueDetails(
                            issue.id(),
                            taigaToken);
            var project =
                    taigaIntegrationService.getProjectById(
                            issueDetails.projectId(),
                            taigaToken);
            taigaIssueUrl =
                    taigaIntegrationService.buildTaigaIssueUrl(
                            project.slug(),
                            issue.ref());
        }
        if (record.isPresent()) {
            glpiIntegrationService.updatePluginFields(
                    ticketId,
                    record.get().id(),
                    issue.id(),
                    taigaIssueUrl,
                    sessionToken);
        } else {
            glpiIntegrationService.createPluginFields(
                    ticketId,
                    issue.id(),
                    taigaIssueUrl,
                    sessionToken);
        }

        String dataPrevista = null;

        if (issue.dueDate() != null) {
            dataPrevista = issue.dueDate().split("T")[0];
        }

        glpiIntegrationService.syncExternalProgress(
                ticketId,
                statusNome,
                dataPrevista,
                sessionToken);

        log.info(
                "TAIGA - Ticket {} atualizado via issue: status='{}', dataPrevista={}.",
                ticketId,
                statusNome,
                dataPrevista);
    }

    /**
     * Valida e inicia o tratamento de webhooks associados a User Stories independentes no Taiga.
     */
    private void processTaigaUserStoryEvent(TaigaIssueData data) {

        if (data.id() == null || data.generatedFromIssue() == null) {
            log.info(
                    "TAIGA - História {} sem issue de origem, ignorando.",
                    data.id());
            return;
        }

        if (data.status() == null) {
            log.warn("TAIGA - Payload de história inválido (status ausente).");
            return;
        }

        withAuthRetry(new Runnable() {

            @Override
            public void run() {
                doProcessUserStoryUpdate(data);
            }

        });
    }

    /**
     * Executa a atualização do GLPI com base em alterações na User Story associada.
     */
    private void doProcessUserStoryUpdate(TaigaIssueData us) {

        String sessionToken =
                glpiIntegrationService.initSession();

        Optional<Long> glpiTicketId =
                glpiIntegrationService.getTicketByIdTaiga(
                        us.generatedFromIssue(),
                        sessionToken);

        if (glpiTicketId.isEmpty()) {

            log.warn(
                    "TAIGA - Nenhum ticket GLPI para issue {} (origem da história {}).",
                    us.generatedFromIssue(),
                    us.id());

            return;
        }

        Long ticketId = glpiTicketId.get();
        String statusName = us.status().name();

        String expectedDate = null;

        if (us.dueDate() != null) {
            expectedDate = us.dueDate().split("T")[0];
        }

        glpiIntegrationService.syncExternalProgress(
                ticketId,
                statusName,
                expectedDate,
                sessionToken);

        log.info(
                "TAIGA - Ticket {} atualizado via história {}: status='{}', expectedDate={}.",
                ticketId,
                us.id(),
                statusName,
                expectedDate);
    }

    /**
     * Mecanismo de execução resiliente que intercepta falhas de autenticação
     * e dispara novas tentativas baseadas no limite máximo configurado.
     */
    private void withAuthRetry(Runnable action) {

        IntegrationAuthenticationException lastException = null;

        for (int attempt = 0; attempt <= maxAuthRetries; attempt++) {

            try {

                action.run();
                return;

            } catch (IntegrationAuthenticationException e) {

                lastException = e;

                log.warn(
                        "Auth falhou (tentativa {}/{}). Motivo: {}",
                        attempt + 1,
                        maxAuthRetries + 1,
                        e.getMessage());
            }
        }

        if (lastException != null) {
            failureLogService.logFailure("Esgotadas as tentativas de autenticação", lastException);
            throw lastException;
        }
    }
}