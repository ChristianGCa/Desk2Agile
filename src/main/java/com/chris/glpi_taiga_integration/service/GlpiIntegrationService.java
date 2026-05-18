package com.chris.glpi_taiga_integration.service;

import com.chris.glpi_taiga_integration.dto.GlpiEntityResponse;
import com.chris.glpi_taiga_integration.config.GlpiPluginFieldsProperties;
import com.chris.glpi_taiga_integration.dto.GlpiPluginFieldsRecord;
import com.chris.glpi_taiga_integration.dto.GlpiSessionResponse;
import com.chris.glpi_taiga_integration.dto.GlpiTicketResponse;
import com.chris.glpi_taiga_integration.dto.GlpiUpdateTicketRequest;
import com.chris.glpi_taiga_integration.exception.GlpiPluginFieldsException;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class GlpiIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(GlpiIntegrationService.class);

    static final String GLPI_SESSION_CACHE = "glpiSession";
    static final String CACHE_KEY = "'token'";

    private final RestClient restClient;
    private final CacheManager cacheManager;
    private final GlpiPluginFieldsProperties pluginFieldsProperties;

    @Value("${glpi.api.url}")
    private String glpiApiUrl;

    @Value("${glpi.api.app-token}")
    private String glpiAppToken;

    @Value("${glpi.api.user-token}")
    private String glpiUserToken;

    public GlpiIntegrationService(
            RestClient restClient,
            CacheManager cacheManager,
            GlpiPluginFieldsProperties pluginFieldsProperties) {
        this.restClient = restClient;
        this.cacheManager = cacheManager;
        this.pluginFieldsProperties = pluginFieldsProperties;
    }

    // ------------------------------------------------------------------
    // Roteamento de paths do plugin Fields
    //
    // O GLPI gera o endpoint do plugin Fields a partir do nome do bloco
    // removendo acentos, espaços e especiais (tudo minúsculo).
    //
    // Exemplos:
    //   "Taiga"                → /PluginFieldsTickettaiga
    //   "Progresso do chamado" → /PluginFieldsTicketprogressodochamado
    // ------------------------------------------------------------------

    private String buildPluginPathNormalized(String blockName) {
        // GLPI descarta caracteres não-ASCII (não transliteram via NFD).
        String sanitized = blockName
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return "/PluginFieldsTicket" + sanitized;
    }

    private List<String> buildPluginPathCandidates(String blockName) {
        return List.of(buildPluginPathNormalized(blockName));
    }

    private boolean isResourceNotFound(RestClientResponseException e) {
        String response = e.getResponseBodyAsString();
        return e.getStatusCode().is4xxClientError()
                && response != null
                && response.contains("ERROR_RESOURCE_NOT_FOUND_NOR_COMMONDBTM");
    }

    private String pluginFieldsDiagnosticMessage(String blockName, List<String> triedPaths) {
        return "Falha ao localizar bloco do plugin Fields no GLPI. "
                + "Bloco configurado='" + blockName + "', "
                + "paths tentados=" + triedPaths + ". "
                + "Campos privados (bloco Taiga): ["
                + pluginFieldsProperties.privateIdTaigaApiField() + ", "
                + pluginFieldsProperties.privateLinkTaigaApiField() + "]. "
                + "Campos públicos (bloco Progresso do chamado): ["
                + pluginFieldsProperties.publicStatusChamadoApiField() + ", "
                + pluginFieldsProperties.publicDataPrevistaApiField() + "]. "
                + "Verifique se o nome do bloco e dos campos no application.yaml correspondem ao GLPI.";
    }

    // ------------------------------------------------------------------
    // Operações genéricas de CRUD no plugin Fields
    // ------------------------------------------------------------------

    private List<Map<String, Object>> fetchPluginRecords(
            String blockName, String sessionToken, int offset, int limit, String operationLabel) {
        List<String> triedPaths = new ArrayList<>();
        RestClientResponseException lastException = null;

        for (String path : buildPluginPathCandidates(blockName)) {
            triedPaths.add(path);
            try {
                String uri = glpiApiUrl + path + "?range=" + offset + "-" + (offset + limit - 1);
                return restClient.get()
                        .uri(uri)
                        .header("Session-Token", sessionToken)
                        .header("App-Token", glpiAppToken)
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            } catch (RestClientResponseException e) {
                lastException = e;
                if (isResourceNotFound(e)) {
                    log.warn("GLPI SERVICE - {} falhou no path {}: recurso não encontrado no GLPI.",
                            operationLabel, path);
                    continue;
                }
                throw new GlpiPluginFieldsException(
                        "Erro ao consultar plugin Fields no GLPI durante " + operationLabel
                                + ". Status HTTP=" + e.getRawStatusCode()
                                + ", resposta=" + e.getResponseBodyAsString(),
                        e);
            }
        }

        throw new GlpiPluginFieldsException(pluginFieldsDiagnosticMessage(blockName, triedPaths), lastException);
    }

    private void createPluginRecord(String blockName, Map<String, Object> body, String sessionToken, String operationLabel) {
        List<String> triedPaths = new ArrayList<>();
        RestClientResponseException lastException = null;

        for (String path : buildPluginPathCandidates(blockName)) {
            triedPaths.add(path);
            try {
                restClient.post()
                        .uri(glpiApiUrl + path)
                        .header("Session-Token", sessionToken)
                        .header("App-Token", glpiAppToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (RestClientResponseException e) {
                lastException = e;
                if (isResourceNotFound(e)) {
                    log.warn("GLPI SERVICE - {} falhou no path {}: recurso não encontrado no GLPI.",
                            operationLabel, path);
                    continue;
                }
                throw new GlpiPluginFieldsException(
                        "Erro ao criar registro no plugin Fields durante " + operationLabel
                                + ". Status HTTP=" + e.getRawStatusCode()
                                + ", resposta=" + e.getResponseBodyAsString(),
                        e);
            }
        }

        throw new GlpiPluginFieldsException(pluginFieldsDiagnosticMessage(blockName, triedPaths), lastException);
    }

    private void updatePluginRecord(
            String blockName, Long recordId, Map<String, Object> body, String sessionToken, String operationLabel) {
        List<String> triedPaths = new ArrayList<>();
        RestClientResponseException lastException = null;

        for (String path : buildPluginPathCandidates(blockName)) {
            triedPaths.add(path);
            try {
                restClient.put()
                        .uri(glpiApiUrl + path + "/" + recordId)
                        .header("Session-Token", sessionToken)
                        .header("App-Token", glpiAppToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (RestClientResponseException e) {
                lastException = e;
                if (isResourceNotFound(e)) {
                    log.warn("GLPI SERVICE - {} falhou no path {}: recurso não encontrado no GLPI.",
                            operationLabel, path);
                    continue;
                }
                throw new GlpiPluginFieldsException(
                        "Erro ao atualizar registro no plugin Fields durante " + operationLabel
                                + ". Status HTTP=" + e.getRawStatusCode()
                                + ", resposta=" + e.getResponseBodyAsString(),
                        e);
            }
        }

        throw new GlpiPluginFieldsException(pluginFieldsDiagnosticMessage(blockName, triedPaths), lastException);
    }

    // ------------------------------------------------------------------
    // Sessão GLPI
    // ------------------------------------------------------------------

    @Cacheable(value = GLPI_SESSION_CACHE, key = CACHE_KEY)
    public String initSession() {
        log.info("GLPI SERVICE - Iniciando nova sessão no GLPI...");
        GlpiSessionResponse sessionResponse = restClient.get()
                .uri(glpiApiUrl + "/initSession")
                .header("App-Token", glpiAppToken)
                .header("Authorization", "user_token " + glpiUserToken)
                .retrieve()
                .body(GlpiSessionResponse.class);

        if (sessionResponse == null || sessionResponse.sessionToken() == null) {
            throw new RuntimeException("Falha ao iniciar sessão no GLPI: session_token não retornado.");
        }

        log.info("GLPI SERVICE - Sessão GLPI iniciada (token em cache).");
        return sessionResponse.sessionToken();
    }

    public void invalidateGlpiSession() {
        Optional.ofNullable(cacheManager.getCache(GLPI_SESSION_CACHE))
                .ifPresent(cache -> {
                    cache.clear();
                    log.info("GLPI SERVICE - Cache de sessão GLPI invalidado.");
                });
    }

    public void closeGlpiSession(String sessionToken) {
        log.info("GLPI SERVICE - Encerrando sessão GLPI no servidor...");
        try {
            restClient.get()
                    .uri(glpiApiUrl + "/killSession")
                    .header("Session-Token", sessionToken)
                    .header("App-Token", glpiAppToken)
                    .retrieve()
                    .toBodilessEntity();
            log.info("GLPI SERVICE - Sessão GLPI encerrada no servidor.");
        } catch (Exception e) {
            log.warn("GLPI SERVICE - Não foi possível encerrar a sessão GLPI: {}", e.getMessage());
        } finally {
            invalidateGlpiSession();
        }
    }

    @PreDestroy
    public void onShutdown() {
        log.info("GLPI SERVICE - Shutdown detectado. Encerrando sessão GLPI...");
        try {
            String token = initSession();
            closeGlpiSession(token);
        } catch (Exception e) {
            log.warn("GLPI SERVICE - Não foi possível fechar a sessão GLPI no shutdown: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Bloco privado "Taiga" — campos: ID Taiga, Link Taiga
    // Rota: /PluginFieldsTickettaiga
    // ------------------------------------------------------------------

    public Optional<GlpiPluginFieldsRecord> getPluginFieldsRecord(Long ticketId, String sessionToken) {
        log.info("GLPI SERVICE - Buscando registro do bloco Taiga para ticket {}.", ticketId);
        String blockName = pluginFieldsProperties.getPrivateTicketStatusBlockName();

        int offset = 0;
        int limit = 100;
        boolean hasMoreRecords = true;

        while (hasMoreRecords) {
            List<Map<String, Object>> records = fetchPluginRecords(
                    blockName, sessionToken, offset, limit, "consulta do bloco Taiga");

            if (records == null || records.isEmpty()) {
                break;
            }

            Optional<GlpiPluginFieldsRecord> record = records.stream()
                    .map(this::toPrivateRecord)
                    .filter(r -> ticketId.equals(r.itemsId()))
                    .findFirst();

            if (record.isPresent()) {
                return record;
            }

            offset += limit;
            if (records.size() < limit) {
                hasMoreRecords = false;
            }
        }
        return Optional.empty();
    }

    public void updateGlpiTicket(Long ticketId, Long taigaIssueId, String taigaIssueUrl, String sessionToken) {
        log.info("GLPI SERVICE - Atualizando chamado no GLPI (ticket={})...", ticketId);
        updateGlpiTaigaId(ticketId, taigaIssueId, sessionToken);

        Optional<GlpiPluginFieldsRecord> record = getPluginFieldsRecord(ticketId, sessionToken);
        if (record.isPresent()) {
            updatePluginFields(ticketId, record.get().id(), taigaIssueId, taigaIssueUrl, sessionToken);
        } else {
            createPluginFields(ticketId, taigaIssueId, taigaIssueUrl, sessionToken);
        }
    }

    public void updateGlpiTaigaId(Long ticketId, Long taigaIssueId, String sessionToken) {
        log.info("GLPI SERVICE - Atualizando campo ID Taiga no ticket {}...", ticketId);
        restClient.put()
                .uri(glpiApiUrl + "/Ticket/" + ticketId)
                .header("Session-Token", sessionToken)
                .header("App-Token", glpiAppToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(GlpiUpdateTicketRequest.of(taigaIssueId))
                .retrieve()
                .toBodilessEntity();
    }

    public Optional<Long> getTicketByIdTaiga(Long taigaIssueId, String sessionToken) {
        log.info("GLPI SERVICE - Buscando chamado pelo campo {}={} (com paginação).",
                pluginFieldsProperties.privateIdTaigaApiField(), taigaIssueId);
        String blockName = pluginFieldsProperties.getPrivateTicketStatusBlockName();

        int offset = 0;
        int limit = 100;
        boolean hasMoreRecords = true;
        String searchId = String.valueOf(taigaIssueId);

        while (hasMoreRecords) {
            List<Map<String, Object>> records = fetchPluginRecords(
                    blockName, sessionToken, offset, limit, "busca por ID Taiga no bloco Taiga");

            if (records == null || records.isEmpty()) {
                break;
            }

            Optional<GlpiPluginFieldsRecord> matchedRecord = records.stream()
                    .map(this::toPrivateRecord)
                    .filter(r -> searchId.equals(r.taigaIdValue()))
                    .findFirst();

            if (matchedRecord.isPresent()) {
                Long ticketId = matchedRecord.get().itemsId();
                log.info("GLPI SERVICE - Ticket GLPI encontrado com sucesso: ID={}.", ticketId);
                return Optional.of(ticketId);
            }

            offset += limit;
            if (records.size() < limit) {
                hasMoreRecords = false;
            }
        }

        log.warn("GLPI SERVICE - Nenhum ticket GLPI corresponde à issue do Taiga {}.", taigaIssueId);
        return Optional.empty();
    }

    /**
     * Cria registro no bloco "Taiga" com ID Taiga e Link Taiga.
     */
    public void createPluginFields(Long ticketId, Long taigaIssueId, String taigaIssueUrl, String sessionToken) {
        createPluginRecord(
                pluginFieldsProperties.getPrivateTicketStatusBlockName(),
                buildPrivatePluginFieldsBody(ticketId, taigaIssueId, taigaIssueUrl),
                sessionToken,
                "criação de registro no bloco Taiga");
    }

    /**
     * Atualiza registro no bloco "Taiga" com ID Taiga e Link Taiga.
     */
    public void updatePluginFields(Long ticketId, Long recordId, Long taigaIssueId, String taigaIssueUrl, String sessionToken) {
        updatePluginRecord(
                pluginFieldsProperties.getPrivateTicketStatusBlockName(),
                recordId,
                buildPrivatePluginFieldsBody(ticketId, taigaIssueId, taigaIssueUrl),
                sessionToken,
                "atualização de registro no bloco Taiga");
    }

    // ------------------------------------------------------------------
    // Bloco público "Progresso do chamado" — campos: Status do chamado, Data prevista
    // Rota: /PluginFieldsTicketprogressodochamado
    // ------------------------------------------------------------------

    public Optional<GlpiPluginFieldsRecord> getExternalProgressRecord(Long ticketId, String sessionToken) {
        log.info("GLPI SERVICE - Buscando registro Progresso do chamado para ticket {}.", ticketId);
        String blockName = pluginFieldsProperties.getPublicTicketStatusBlockName();

        int offset = 0;
        int limit = 100;
        boolean hasMoreRecords = true;

        while (hasMoreRecords) {
            List<Map<String, Object>> records = fetchPluginRecords(
                    blockName, sessionToken, offset, limit, "consulta do bloco Progresso do chamado");

            if (records == null || records.isEmpty()) {
                break;
            }

            Optional<GlpiPluginFieldsRecord> record = records.stream()
                    .map(this::toPublicRecord)
                    .filter(r -> ticketId.equals(r.itemsId()))
                    .findFirst();

            if (record.isPresent()) {
                return record;
            }

            offset += limit;
            if (records.size() < limit) {
                hasMoreRecords = false;
            }
        }

        return Optional.empty();
    }

    /**
     * Cria registro no bloco "Progresso do chamado".
     *
     * @param dataPrevista data no formato aceito pelo GLPI (ex.: "2024-12-31"); pode ser null.
     */
    public void createExternalProgress(Long ticketId, String status, String dataPrevista, String sessionToken) {
        createPluginRecord(
                pluginFieldsProperties.getPublicTicketStatusBlockName(),
                buildPublicPluginFieldsBody(ticketId, status, dataPrevista),
                sessionToken,
                "criação de registro no bloco Progresso do chamado");
    }

    /**
     * Atualiza registro no bloco "Progresso do chamado".
     *
     * @param dataPrevista data no formato aceito pelo GLPI (ex.: "2024-12-31"); pode ser null.
     */
    public void updateExternalProgress(Long ticketId, Long recordId, String status, String dataPrevista, String sessionToken) {
        updatePluginRecord(
                pluginFieldsProperties.getPublicTicketStatusBlockName(),
                recordId,
                buildPublicPluginFieldsBody(ticketId, status, dataPrevista),
                sessionToken,
                "atualização de registro no bloco Progresso do chamado");
    }

    /**
     * Cria ou atualiza o bloco "Progresso do chamado".
     *
     * @param dataPrevista data no formato aceito pelo GLPI (ex.: "2024-12-31"); pode ser null.
     */
    public void syncExternalProgress(Long ticketId, String status, String dataPrevista, String sessionToken) {
        Optional<GlpiPluginFieldsRecord> record = getExternalProgressRecord(ticketId, sessionToken);
        if (record.isPresent()) {
            updateExternalProgress(ticketId, record.get().id(), status, dataPrevista, sessionToken);
        } else {
            createExternalProgress(ticketId, status, dataPrevista, sessionToken);
        }
    }

    public String getInitialStatusExternalProgress() {
        return pluginFieldsProperties.getStatusInicial();
    }

    // ------------------------------------------------------------------
    // Tickets e Entidades
    // ------------------------------------------------------------------

    public Optional<Long> getTicketEntityId(Long ticketId, String sessionToken) {
        GlpiTicketResponse ticket = restClient.get()
                .uri(glpiApiUrl + "/Ticket/" + ticketId)
                .header("Session-Token", sessionToken)
                .header("App-Token", glpiAppToken)
                .retrieve()
                .body(GlpiTicketResponse.class);

        return ticket == null ? Optional.empty() : Optional.ofNullable(ticket.entitiesId());
    }

    public Optional<GlpiEntityResponse> getEntityById(Long entityId, String sessionToken) {
        if (entityId == null) return Optional.empty();

        GlpiEntityResponse entity = restClient.get()
                .uri(glpiApiUrl + "/Entity/" + entityId)
                .header("Session-Token", sessionToken)
                .header("App-Token", glpiAppToken)
                .retrieve()
                .body(GlpiEntityResponse.class);

        return Optional.ofNullable(entity);
    }

    // ------------------------------------------------------------------
    // Mapeamento de registros brutos
    // ------------------------------------------------------------------

    private GlpiPluginFieldsRecord toPrivateRecord(Map<String, Object> rawRecord) {
        Long id = getRequiredLong(rawRecord, "id");
        Long itemsId = getRequiredLong(rawRecord, "items_id");
        String idTaigaValue = getRequiredString(rawRecord, pluginFieldsProperties.privateIdTaigaApiField());
        String linkTaigaValue = getString(rawRecord, pluginFieldsProperties.privateLinkTaigaApiField());
        return new GlpiPluginFieldsRecord(id, itemsId, idTaigaValue, linkTaigaValue);
    }

    private GlpiPluginFieldsRecord toPublicRecord(Map<String, Object> rawRecord) {
        Long id = getRequiredLong(rawRecord, "id");
        Long itemsId = getRequiredLong(rawRecord, "items_id");
        return new GlpiPluginFieldsRecord(id, itemsId, null, null);
    }

    // ------------------------------------------------------------------
    // Montagem dos corpos das requisições
    // ------------------------------------------------------------------

    /**
     * Corpo do POST/PUT para o bloco "Taiga".
     * Campos: items_id, itemtype, {idTaigaField}, {linkTaigaField}
     */
    private Map<String, Object> buildPrivatePluginFieldsBody(Long ticketId, Long taigaIssueId, String taigaIssueUrl) {
        Map<String, Object> input = new HashMap<>();
        input.put("items_id", ticketId);
        input.put("itemtype", "Ticket");
        input.put(pluginFieldsProperties.privateIdTaigaApiField(), String.valueOf(taigaIssueId));
        input.put(pluginFieldsProperties.privateLinkTaigaApiField(), taigaIssueUrl);
        return Map.of("input", input);
    }

    /**
     * Corpo do POST/PUT para o bloco "Progresso do chamado".
     * Campos: items_id, itemtype, {statusChamadoField}[, {dataPrevistaField}]
     * dataPrevista é opcional — não é incluído no body quando null ou vazio.
     */
    private Map<String, Object> buildPublicPluginFieldsBody(Long ticketId, String status, String dataPrevista) {
        Map<String, Object> input = new HashMap<>();
        input.put("items_id", ticketId);
        input.put("itemtype", "Ticket");
        input.put(pluginFieldsProperties.publicStatusChamadoApiField(), status);
        if (dataPrevista != null && !dataPrevista.isBlank()) {
            input.put(pluginFieldsProperties.publicDataPrevistaApiField(), toGlpiDateFormat(dataPrevista));
        }
        return Map.of("input", input);
    }

    /**
     * Converte data de "YYYY-MM-DD" (formato Taiga) para "DD/MM/YYYY" (formato esperado pelo GLPI Plugin Fields).
     * Se a entrada não estiver no formato esperado, retorna o valor original sem alteração.
     */
    private static String toGlpiDateFormat(String isoDate) {
        if (isoDate == null || !isoDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return isoDate;
        }
        return isoDate.substring(8, 10) + "/" + isoDate.substring(5, 7) + "/" + isoDate.substring(0, 4);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Long getRequiredLong(Map<String, Object> rawRecord, String fieldName) {
        Object value = rawRecord.get(fieldName);
        if (value == null) {
            throw new IllegalStateException("Campo obrigatório não retornado pelo GLPI: " + fieldName);
        }
        if (value instanceof Number numberValue) {
            return numberValue.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Campo '" + fieldName + "' inválido no retorno do GLPI: " + value);
        }
    }

    private String getRequiredString(Map<String, Object> rawRecord, String fieldName) {
        if (!rawRecord.containsKey(fieldName)) {
            throw new IllegalStateException("Campo configurado não encontrado no retorno do GLPI: " + fieldName
                    + ". Verifique glpi.plugin-fields no application.yaml.");
        }
        Object value = rawRecord.get(fieldName);
        return value == null ? "" : String.valueOf(value);
    }

    private String getString(Map<String, Object> rawRecord, String fieldName) {
        Object value = rawRecord.get(fieldName);
        return (value == null || "null".equals(String.valueOf(value))) ? null : String.valueOf(value);
    }
}