package com.chris.glpi_taiga_integration.service;

import com.chris.glpi_taiga_integration.dto.TaigaAuthRequest;
import com.chris.glpi_taiga_integration.dto.TaigaAuthResponse;
import com.chris.glpi_taiga_integration.dto.TaigaIssueDetailsResponse;
import com.chris.glpi_taiga_integration.dto.TaigaIssueRequest;
import com.chris.glpi_taiga_integration.dto.TaigaIssueResponse;
import com.chris.glpi_taiga_integration.dto.TaigaProjectResponse;
import com.chris.glpi_taiga_integration.dto.TaigaUserStoryDetailsResponse;
import com.chris.glpi_taiga_integration.dto.TaigaUserStoryStatusResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Serviço responsável pela comunicação direta com a API REST do Taiga.
 * Centraliza as operações de autenticação, criação de issues e consultas
 * de projetos ou user stories, utilizando abstrações de cache do Spring.
 */
@Service
public class TaigaIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(TaigaIntegrationService.class);

    /** Nome do cache utilizado para armazenar o token de autenticação do Taiga. */
    static final String TAIGA_TOKEN_CACHE = "taigaToken";

    /** Chave estática para indexação do token único no cache. */
    static final String CACHE_KEY = "'token'";

    private final RestClient restClient;
    private final CacheManager cacheManager;
    private static final String AUTH_TYPE_NORMAL = "normal";

    @Value("${taiga.api.url}")
    private String taigaApiUrl;

    @Value("${taiga.api.username}")
    private String taigaUsername;

    @Value("${taiga.api.password}")
    private String taigaPassword;

    @Value("${taiga.web.url}")
    private String taigaWebUrl;

    /**
     * Construtor para injeção de dependências do Spring.
     *
     * @param restClient Cliente HTTP para as requisições.
     * @param cacheManager Gerenciador de cache para controle manual de invalidação.
     */
    public TaigaIntegrationService(RestClient restClient, CacheManager cacheManager) {
        this.restClient = restClient;
        this.cacheManager = cacheManager;
    }

    /**
     * Realiza a autenticação na API do Taiga utilizando as credenciais configuradas.
     * O resultado é cacheado para evitar chamadas repetitivas de login.
     *
     * @return O token de autenticação (JWT) gerado pelo Taiga.
     * @throws RuntimeException Se a API responder sem um token válido.
     */
    @Cacheable(value = TAIGA_TOKEN_CACHE, key = CACHE_KEY)
    public String authenticateInTaiga() {
        log.debug("TAIGA SERVICE - Autenticando no Taiga (nova requisição)...");
        TaigaAuthRequest authRequest = new TaigaAuthRequest(AUTH_TYPE_NORMAL, taigaUsername, taigaPassword);

        TaigaAuthResponse authResponse = restClient.post()
                .uri(taigaApiUrl + "/auth")
                .contentType(MediaType.APPLICATION_JSON)
                .body(authRequest)
                .retrieve()
                .body(TaigaAuthResponse.class);

        if (authResponse == null || authResponse.authToken() == null) {
            throw new RuntimeException("Falha na autenticação: token não retornado pelo Taiga.");
        }

        log.debug("TAIGA SERVICE - Autenticação Taiga concluída (token em cache).");
        return authResponse.authToken();
    }

    /**
     * Remove manualmente o token do Taiga armazenado em cache, forçando
     * uma nova autenticação na próxima chamada do serviço.
     */
    public void invalidateTaigaToken() {
        Optional.ofNullable(cacheManager.getCache(TAIGA_TOKEN_CACHE))
                .ifPresent(cache -> {
                    cache.clear();
                    log.debug("TAIGA SERVICE - Cache de token Taiga invalidado.");
                });
    }

    /**
     * Cria uma nova Issue (Incidente) em um projeto específico do Taiga.
     *
     * @param projectId ID numérico do projeto no Taiga.
     * @param title Título da issue.
     * @param description Detalhamento/conteúdo da issue.
     * @param token Token de autorização válido.
     * @return DTO com a resposta de criação da issue.
     */
    public TaigaIssueResponse createIssueOnTaiga(Long projectId, String title, String description, String token) {
        log.info("TAIGA SERVICE - Criando issue no Taiga: projetoId={} título='{}'.", projectId, title);
        TaigaIssueRequest requestBody = new TaigaIssueRequest(
                projectId, title, description, null, null, null);

        return restClient.post()
                .uri(taigaApiUrl + "/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .body(requestBody)
                .retrieve()
                .body(TaigaIssueResponse.class);
    }

    /**
     * Monta a URL de visualização web para uma determinada Issue.
     *
     * @param projectSlug Identificador textual do projeto (slug).
     * @param ref Código de referência sequencial da issue.
     * @return URL absoluta da issue ou null se os parâmetros forem inválidos.
     */
    public String buildTaigaIssueUrl(String projectSlug, Long ref) {
        if (ref == null || projectSlug == null || projectSlug.isBlank()) return null;
        return normalizeBaseUrl() + "/project/" + projectSlug + "/issue/" + ref;
    }

    /**
     * Monta a URL de visualização web para uma determinada User Story (História de Usuário).
     *
     * @param projectSlug Identificador textual do projeto (slug).
     * @param ref Código de referência sequencial da história.
     * @return URL absoluta da user story ou null se os parâmetros forem inválidos.
     */
    public String buildTaigaUserStoryUrl(String projectSlug, Long ref) {
        if (ref == null || projectSlug == null || projectSlug.isBlank()) return null;
        return normalizeBaseUrl() + "/project/" + projectSlug + "/us/" + ref;
    }

    /**
     * Remove barras residuais ao final da URL base do Taiga Web para evitar caminhos duplicados.
     */
    private String normalizeBaseUrl() {
        return taigaWebUrl.endsWith("/")
                ? taigaWebUrl.substring(0, taigaWebUrl.length() - 1)
                : taigaWebUrl;
    }

    /**
     * Recupera os metadados de um projeto a partir do seu slug. O resultado é cacheado.
     *
     * @param slug O slug identificador do projeto.
     * @param token Token de autorização válido.
     * @return Resposta contendo os dados do projeto.
     */
    @Cacheable(value = "taigaProjects", key = "'slug:' + #slug")
    public TaigaProjectResponse getProjectBySlug(String slug, String token) {
        log.debug("TAIGA SERVICE - Buscando projeto por slug='{}'.", slug);
        return restClient.get()
                .uri(taigaApiUrl + "/projects/by_slug?slug=" + slug)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(TaigaProjectResponse.class);
    }

    /**
     * Recupera os metadados de um projeto a partir de seu ID numérico. O resultado é cacheado.
     *
     * @param projectId ID numérico do projeto.
     * @param token Token de autorização válido.
     * @return Resposta contendo os dados do projeto.
     */
    @Cacheable(value = "taigaProjects", key = "'id:' + #projectId")
    public TaigaProjectResponse getProjectById(Long projectId, String token) {
        log.debug("TAIGA SERVICE - Buscando projeto por id={}.", projectId);
        return restClient.get()
                .uri(taigaApiUrl + "/projects/" + projectId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(TaigaProjectResponse.class);
    }

    /**
     * Obtém os detalhes completos de uma Issue específica do Taiga.
     *
     * @param issueId ID interno da issue.
     * @param token Token de autorização válido.
     * @return Objeto com detalhes da issue.
     */
    public TaigaIssueDetailsResponse getIssueDetails(Long issueId, String token) {
        log.debug("TAIGA SERVICE - Buscando detalhes da issue id={}.", issueId);
        return restClient.get()
                .uri(taigaApiUrl + "/issues/" + issueId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(TaigaIssueDetailsResponse.class);
    }

    /**
     * Obtém os detalhes completos de uma User Story específica do Taiga.
     *
     * @param userStoryId ID interno da história de usuário.
     * @param token Token de autorização válido.
     * @return Objeto com detalhes da user story.
     */
    public TaigaUserStoryDetailsResponse getUserStoryDetails(Long userStoryId, String token) {
        log.debug("TAIGA SERVICE - Buscando detalhes da história id={}.", userStoryId);
        return restClient.get()
                .uri(taigaApiUrl + "/userstories/" + userStoryId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(TaigaUserStoryDetailsResponse.class);
    }

    /**
     * Traduz o ID de um status de User Story para seu respectivo nome mapeado na plataforma.
     *
     * @param statusId ID interno do status da história.
     * @param token Token de autorização válido.
     * @return Objeto contendo o nome e propriedades do status.
     */
    public TaigaUserStoryStatusResponse getUserStoryStatus(Long statusId, String token) {
        log.debug("TAIGA SERVICE - Buscando nome do status de história id={}.", statusId);
        return restClient.get()
                .uri(taigaApiUrl + "/userstory-statuses/" + statusId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(TaigaUserStoryStatusResponse.class);
    }
}