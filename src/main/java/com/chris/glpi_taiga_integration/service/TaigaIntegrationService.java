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

@Service
public class TaigaIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(TaigaIntegrationService.class);

    static final String TAIGA_TOKEN_CACHE = "taigaToken";
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

    public TaigaIntegrationService(RestClient restClient, CacheManager cacheManager) {
        this.restClient = restClient;
        this.cacheManager = cacheManager;
    }

    @Cacheable(value = TAIGA_TOKEN_CACHE, key = CACHE_KEY)
    public String authenticateInTaiga() {
        log.info("TAIGA SERVICE - Autenticando no Taiga (nova requisição)...");
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

        log.info("TAIGA SERVICE - Autenticação Taiga concluída (token em cache).");
        return authResponse.authToken();
    }

    public void invalidateTaigaToken() {
        Optional.ofNullable(cacheManager.getCache(TAIGA_TOKEN_CACHE))
                .ifPresent(cache -> {
                    cache.clear();
                    log.info("TAIGA SERVICE - Cache de token Taiga invalidado.");
                });
    }

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

    public String buildTaigaIssueUrl(String projectSlug, Long ref) {
        if (ref == null || projectSlug == null || projectSlug.isBlank()) return null;
        return normalizeBaseUrl() + "/project/" + projectSlug + "/issue/" + ref;
    }

    public String buildTaigaUserStoryUrl(String projectSlug, Long ref) {
        if (ref == null || projectSlug == null || projectSlug.isBlank()) return null;
        return normalizeBaseUrl() + "/project/" + projectSlug + "/us/" + ref;
    }

    private String normalizeBaseUrl() {
        return taigaWebUrl.endsWith("/")
                ? taigaWebUrl.substring(0, taigaWebUrl.length() - 1)
                : taigaWebUrl;
    }

    @Cacheable(value = "taigaProjects", key = "'slug:' + #slug")
    public TaigaProjectResponse getProjectBySlug(String slug, String token) {
        log.info("TAIGA SERVICE - Buscando projeto por slug='{}'.", slug);
        return restClient.get()
                .uri(taigaApiUrl + "/projects/by_slug?slug=" + slug)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(TaigaProjectResponse.class);
    }

    @Cacheable(value = "taigaProjects", key = "'id:' + #projectId")
    public TaigaProjectResponse getProjectById(Long projectId, String token) {
        log.info("TAIGA SERVICE - Buscando projeto por id={}.", projectId);
        return restClient.get()
                .uri(taigaApiUrl + "/projects/" + projectId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(TaigaProjectResponse.class);
    }

    public TaigaIssueDetailsResponse getIssueDetails(Long issueId, String token) {
        log.info("TAIGA SERVICE - Buscando detalhes da issue id={}.", issueId);
        return restClient.get()
                .uri(taigaApiUrl + "/issues/" + issueId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(TaigaIssueDetailsResponse.class);
    }

    public TaigaUserStoryDetailsResponse getUserStoryDetails(Long userStoryId, String token) {
        log.info("TAIGA SERVICE - Buscando detalhes da história id={}.", userStoryId);
        return restClient.get()
                .uri(taigaApiUrl + "/userstories/" + userStoryId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(TaigaUserStoryDetailsResponse.class);
    }

    public TaigaUserStoryStatusResponse getUserStoryStatus(Long statusId, String token) {
        log.info("TAIGA SERVICE - Buscando nome do status de história id={}.", statusId);
        return restClient.get()
                .uri(taigaApiUrl + "/userstory-statuses/" + statusId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(TaigaUserStoryStatusResponse.class);
    }
}
