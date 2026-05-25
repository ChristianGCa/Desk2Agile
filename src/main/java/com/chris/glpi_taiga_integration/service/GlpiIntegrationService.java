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
    /**
     * O GLPI gera o nome do tipo (e o endpoint REST) do plugin Fields a partir do nome do bloco.
     * Ele remove acentos, espaços e especiais.
     * Exemplos:
     * "Taiga" → "PluginFieldsTickettaiga"  (endpoint: /PluginFieldsTickettaiga)
     * "Progresso do chamado" → "PluginFieldsTicketprogressodochamado"
     */
    private String buildItemTypeName(String blockName) {
        String sanitized = blockName
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return "PluginFieldsTicket" + sanitized;
    }

    private String buildPluginPathNormalized(String blockName) {
        return "/" + buildItemTypeName(blockName);
    }

    /**
     * Retorna a lista de caminhos prováveis para o endpoint do plugin.
     */
    private List<String> buildPluginPathCandidates(String blockName) {
        return List.of(buildPluginPathNormalized(blockName));
    }

    // Filtro server-side via searchText no endpoint Get all items

    /**
     * Busca um único registro de um bloco do plugin Fields usando o parâmetro {@code searchText}
     * do endpoint "Get all items" ({@code GET /{itemtype}?searchText[field]=value&range=0-0}).
     * @param blockName  nome do bloco (ex: "Taiga", "Progresso do chamado")
     * @param fieldName  nome do campo a filtrar (ex: "items_id", "idtaigafield")
     * @param fieldValue valor esperado
     */
    private Optional<Map<String, Object>> findPluginRecord(
            String blockName, String fieldName, String fieldValue, String sessionToken) {

        String path = buildPluginPathNormalized(blockName);
        String uri  = glpiApiUrl + path
                + "?searchText[" + fieldName + "]=" + fieldValue
                + "&range=0-0";

        try {
            List<Map<String, Object>> records = restClient.get()
                    .uri(uri)
                    .header("Session-Token", sessionToken)
                    .header("App-Token", glpiAppToken)
                    .header("Cache-Control", "no-cache")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });

            if (records == null || records.isEmpty()) return Optional.empty();
            return Optional.of(records.getFirst());

        } catch (RestClientResponseException e) {
            if (isResourceNotFound(e)) return Optional.empty();
            throw new GlpiPluginFieldsException(
                    "Erro ao buscar registro no bloco '" + blockName + "' com searchText["
                            + fieldName + "]=" + fieldValue
                            + ". Status=" + e.getStatusCode()
                            + ", resposta=" + e.getResponseBodyAsString(),
                    e);
        }
    }

    /**
     * Identifica se o erro retornado pelo GLPI indica que o recurso/bloco não existe.
     */
    private boolean isResourceNotFound(RestClientResponseException e) {
        String response = e.getResponseBodyAsString();
        return e.getStatusCode().is4xxClientError() && response.contains("ERROR_RESOURCE_NOT_FOUND_NOR_COMMONDBTM");
    }

    /**
     * Constrói mensagem detalhada de diagnóstico para falhas de mapeamento do plugin Fields.
     */
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

    // Operações genéricas de CRUD no plugin Fields

    /**
     * Insere um novo registro em um bloco do plugin Fields.
     */
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
                                + ". Status HTTP=" + e.getStatusCode()
                                + ", resposta=" + e.getResponseBodyAsString(),
                        e);
            }
        }

        throw new GlpiPluginFieldsException(pluginFieldsDiagnosticMessage(blockName, triedPaths), lastException);
    }

    /**
     * Atualiza um registro existente em um bloco do plugin Fields via método PUT.
     */
    private void updatePluginRecord(String blockName, Long recordId, Map<String, Object> body, String sessionToken, String operationLabel) {
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
                                + ". Status HTTP=" + e.getStatusCode()
                                + ", resposta=" + e.getResponseBodyAsString(),
                        e);
            }
        }

        throw new GlpiPluginFieldsException(pluginFieldsDiagnosticMessage(blockName, triedPaths), lastException);
    }

    // Sessão do GLPI

    /**
     * Inicializa uma sessão na API do GLPI e armazena o token resultante em cache.
     *
     * @return String contendo o token da sessão ativa.
     */
    @Cacheable(value = GLPI_SESSION_CACHE, key = CACHE_KEY)
    public String initSession() {
        log.debug("GLPI SERVICE - Iniciando nova sessão no GLPI...");
        GlpiSessionResponse sessionResponse = restClient.get()
                .uri(glpiApiUrl + "/initSession")
                .header("App-Token", glpiAppToken)
                .header("Authorization", "user_token " + glpiUserToken)
                .retrieve()
                .body(GlpiSessionResponse.class);

        if (sessionResponse == null || sessionResponse.sessionToken() == null) {
            throw new RuntimeException("Falha ao iniciar sessão no GLPI: session_token não retornado.");
        }

        log.debug("GLPI SERVICE - Sessão GLPI iniciada (token em cache).");
        return sessionResponse.sessionToken();
    }

    /**
     * Remove o token de sessão do cache local da aplicação.
     */
    public void invalidateGlpiSession() {
        Optional.ofNullable(cacheManager.getCache(GLPI_SESSION_CACHE))
                .ifPresent(cache -> {
                    cache.clear();
                    log.debug("GLPI SERVICE - Cache de sessão GLPI invalidado.");
                });
    }

    /**
     * Encerra a sessão ativa no servidor do GLPI e limpa o cache local.
     */
    public void closeGlpiSession(String sessionToken) {
        log.debug("GLPI SERVICE - Encerrando sessão GLPI no servidor...");
        try {
            restClient.get()
                    .uri(glpiApiUrl + "/killSession")
                    .header("Session-Token", sessionToken)
                    .header("App-Token", glpiAppToken)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("GLPI SERVICE - Sessão GLPI encerrada no servidor.");
        } catch (Exception e) {
            log.warn("GLPI SERVICE - Não foi possível encerrar a sessão GLPI: {}", e.getMessage());
        } finally {
            invalidateGlpiSession();
        }
    }

    /**
     * Hook executado no encerramento da aplicação para derrubar a sessão ativa no GLPI,
     * evitando vazamento de conexões abertas no servidor remoto.
     */
    @PreDestroy
    public void onShutdown() {
        log.debug("GLPI SERVICE - Shutdown detectado. Encerrando sessão GLPI...");
        try {
            String token = initSession();
            closeGlpiSession(token);
        } catch (Exception e) {
            log.warn("GLPI SERVICE - Não foi possível fechar a sessão GLPI no shutdown: {}", e.getMessage());
        }
    }

    // Bloco privado (ID da Issue no Taiga e Link)

    /**
     * Localiza o registro do bloco privado Taiga vinculado ao ticket via {@code searchText} server-side.
     * Uma única requisição — O(1).
     */
    public Optional<GlpiPluginFieldsRecord> getPluginFieldsRecord(Long ticketId, String sessionToken) {
        log.debug("GLPI SERVICE - Buscando registro do bloco Taiga para ticket {}.", ticketId);
        return findPluginRecord(
                pluginFieldsProperties.getPrivateTicketStatusBlockName(),
                "items_id", String.valueOf(ticketId),
                sessionToken)
                .map(this::toPrivateRecord);
    }

    /**
     * Orquestra a sincronização do ticket: atualiza o core do GLPI com o ID da issue do Taiga
     * e insere ou altera o registro correspondente no plugin Fields.
     */
    public void updateGlpiTicket(Long ticketId, Long taigaIssueId, String taigaIssueUrl, String sessionToken) {
        log.debug("GLPI SERVICE - Atualizando chamado no GLPI (ticket={})...", ticketId);
        updateGlpiTaigaId(ticketId, taigaIssueId, sessionToken);

        Optional<GlpiPluginFieldsRecord> record = getPluginFieldsRecord(ticketId, sessionToken);
        if (record.isPresent()) {
            updatePluginFields(ticketId, record.get().id(), taigaIssueId, taigaIssueUrl, sessionToken);
        } else {
            createPluginFields(ticketId, taigaIssueId, taigaIssueUrl, sessionToken);
        }
    }

    /**
     * Atualiza o campo customizado nativo ou mapeado no payload do Ticket principal do GLPI.
     */
    public void updateGlpiTaigaId(Long ticketId, Long taigaIssueId, String sessionToken) {
        log.debug("GLPI SERVICE - Atualizando campo ID Taiga no ticket {}...", ticketId);
        restClient.put()
                .uri(glpiApiUrl + "/Ticket/" + ticketId)
                .header("Session-Token", sessionToken)
                .header("App-Token", glpiAppToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(GlpiUpdateTicketRequest.of(taigaIssueId))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Localiza o ticket GLPI pelo ID da Issue Taiga via {@code searchText} server-side.
     * Uma única requisição — O(1).
     */
    public Optional<Long> getTicketByIdTaiga(Long taigaIssueId, String sessionToken) {
        log.debug("GLPI SERVICE - Buscando chamado pelo campo {}={} via searchText.",
                pluginFieldsProperties.privateIdTaigaApiField(), taigaIssueId);

        return findPluginRecord(
                pluginFieldsProperties.getPrivateTicketStatusBlockName(),
                pluginFieldsProperties.privateIdTaigaApiField(),
                String.valueOf(taigaIssueId),
                sessionToken)
                .map(data -> {
                    Long ticketId = toPrivateRecord(data).itemsId();
                    log.info("GLPI SERVICE - Ticket GLPI encontrado: ID={}.", ticketId);
                    return ticketId;
                });
    }

    public void createPluginFields(Long ticketId, Long taigaIssueId, String taigaIssueUrl, String sessionToken) {
        createPluginRecord(
                pluginFieldsProperties.getPrivateTicketStatusBlockName(),
                buildPrivatePluginFieldsBody(ticketId, taigaIssueId, taigaIssueUrl),
                sessionToken,
                "criação de registro no bloco Taiga");
    }

    public void updatePluginFields(Long ticketId, Long recordId, Long taigaIssueId, String taigaIssueUrl, String sessionToken) {
        updatePluginRecord(
                pluginFieldsProperties.getPrivateTicketStatusBlockName(),
                recordId,
                buildPrivatePluginFieldsBody(ticketId, taigaIssueId, taigaIssueUrl),
                sessionToken,
                "atualização de registro no bloco Taiga");
    }

    // Bloco público (Status do chamado e Data de conclusão prevista)

    /**
     * Recupera o registro do bloco público "Progresso do chamado" via {@code searchText} server-side.
     * Uma única requisição — O(1).
     */
    public Optional<GlpiPluginFieldsRecord> getExternalProgressRecord(Long ticketId, String sessionToken) {
        log.debug("GLPI SERVICE - Buscando registro Progresso do chamado para ticket {}.", ticketId);
        return findPluginRecord(
                pluginFieldsProperties.getPublicTicketStatusBlockName(),
                "items_id", String.valueOf(ticketId),
                sessionToken)
                .map(this::toPublicRecord);
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
     * Executa o Upsert (Cria ou Atualiza) dos indicadores públicos de progresso do chamado vindos do Taiga.
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

    // Tickets e Entidades

    /**
     * Busca o ID da Entidade associada ao ticket para validações de escopo de permissões.
     */
    public Optional<Long> getTicketEntityId(Long ticketId, String sessionToken) {
        GlpiTicketResponse ticket = restClient.get()
                .uri(glpiApiUrl + "/Ticket/" + ticketId)
                .header("Session-Token", sessionToken)
                .header("App-Token", glpiAppToken)
                .retrieve()
                .body(GlpiTicketResponse.class);

        return ticket == null ? Optional.empty() : Optional.ofNullable(ticket.entitiesId());
    }

    /**
     * Busca os metadados de uma Entidade do GLPI.
     */
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

    // Montagem dos corpos das requisições

    /**
     * Monta o padrão JSON aninhado ("input": { ... }) exigido pela API do GLPI para o bloco Taiga.
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
     * Monta o padrão JSON aninhado para o bloco de Progresso Público.
     * Converte o formato da data antes da inserção se aplicável.
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
     * Converte data ISO do Taiga ("YYYY-MM-DD") para o formato PT-BR exigido pelo Plugin Fields do GLPI ("DD/MM/YYYY").
     * Fallback: Se falhar no regex, retorna a string original intacta.
     */
    private static String toGlpiDateFormat(String isoDate) {
        if (isoDate == null || !isoDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return isoDate;
        }
        return isoDate.substring(8, 10) + "/" + isoDate.substring(5, 7) + "/" + isoDate.substring(0, 4);
    }

    // Helpers

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

    // Mapeamento de registros brutos (formato retornado pelo endpoint Get all items)

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

    /**
     * Busca na API do GLPI se um usuário específico está atribuído ao ticket.
     * Necessário pois o payload do webhook de atualização frequentemente sofre de atraso (lag)
     * nas relações (traz o array de usuários desatualizado).
     */
    public boolean isUserAssignedToTicket(Long ticketId, String wantedAssignee, String sessionToken) {
        log.warn("GLPI SERVICE - Buscando usuários atribuídos via API para o ticket {}.", ticketId);
        String uri = glpiApiUrl + "/Ticket_User?searchText[tickets_id]=" + ticketId;
        try {
            List<Map<String, Object>> records = restClient.get()
                    .uri(uri)
                    .header("Session-Token", sessionToken)
                    .header("App-Token", glpiAppToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (records == null || records.isEmpty()) return false;

            for (Map<String, Object> record : records) {
                Object typeObj = record.get("type");
                // type = 2 significa "Atribuído" (Assignee)
                if (typeObj != null && "2".equals(String.valueOf(typeObj))) {
                    Object userIdObj = record.get("users_id");
                    if (userIdObj != null) {
                        long userId = Long.parseLong(String.valueOf(userIdObj));
                        String userUri = glpiApiUrl + "/User/" + userId;
                        try {
                            Map<String, Object> userRecord = restClient.get()
                                    .uri(userUri)
                                    .header("Session-Token", sessionToken)
                                    .header("App-Token", glpiAppToken)
                                    .retrieve()
                                    .body(new ParameterizedTypeReference<>() {});

                            if (userRecord != null) {
                                String name = String.valueOf(userRecord.get("name"));
                                String realname = String.valueOf(userRecord.get("realname"));
                                String firstname = String.valueOf(userRecord.get("firstname"));

                                if (wantedAssignee.equalsIgnoreCase(name) ||
                                        wantedAssignee.equalsIgnoreCase(realname) ||
                                        wantedAssignee.equalsIgnoreCase(firstname)) {
                                    return true;
                                }
                            }
                        } catch (RestClientResponseException e) {
                            if (e.getStatusCode().is4xxClientError() && e.getResponseBodyAsString().contains("ERROR_RESOURCE_NOT_FOUND_NOR_COMMONDBTM")) {
                                continue;
                            }
                            throw e;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("GLPI SERVICE - Erro ao verificar Ticket_User via API para o ticket {}: {}", ticketId, e.getMessage());
        }
        return false;
    }
}