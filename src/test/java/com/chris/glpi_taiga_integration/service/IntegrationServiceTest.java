package com.chris.glpi_taiga_integration.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chris.glpi_taiga_integration.dto.GlpiCategory;
import com.chris.glpi_taiga_integration.dto.GlpiItem;
import com.chris.glpi_taiga_integration.dto.GlpiPluginFieldsRecord;
import com.chris.glpi_taiga_integration.dto.GlpiWebhookPayload;
import com.chris.glpi_taiga_integration.dto.TaigaIssueData;
import com.chris.glpi_taiga_integration.dto.TaigaIssueResponse;
import com.chris.glpi_taiga_integration.dto.TaigaProjectResponse;
import com.chris.glpi_taiga_integration.dto.TaigaStatusData;
import com.chris.glpi_taiga_integration.dto.TaigaWebhookPayload;
import com.chris.glpi_taiga_integration.exception.IntegrationAuthenticationException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class IntegrationServiceTest {

    @Mock private TaigaIntegrationService taigaIntegrationService;
    @Mock private GlpiIntegrationService glpiIntegrationService;
    @Mock private ProjectRoutingService projectRoutingService;

    @InjectMocks
    private IntegrationService integrationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "");
        ReflectionTestUtils.setField(integrationService, "assigneeThatSendToTaiga", "");
        ReflectionTestUtils.setField(integrationService, "maxAuthRetries", 1);
    }

    // -------------------------------------------------------------------------
    // Filtragem por categoria
    // -------------------------------------------------------------------------

    @Test
    void processaGlpi_ambosGatilhosDesligados_ignoraTicket() {
        integrationService.processGlpiWebhook(payloadComCategoria("Desenvolvimento"));

        verifyNoInteractions(glpiIntegrationService);
        verifyNoInteractions(taigaIntegrationService);
    }

    @Test
    void processaGlpi_categoriaWildcard_processaQualquerCategoria() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "*");
        configurarMocksFluxoCompleto();

        integrationService.processGlpiWebhook(payloadComCategoria("Qualquer Coisa"));

        verify(glpiIntegrationService, times(1)).initSession();
    }

    @Test
    void processaGlpi_categoriaEspecificaCorresponde_processa() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "Desenvolvimento");
        configurarMocksFluxoCompleto();

        integrationService.processGlpiWebhook(payloadComCategoria("Desenvolvimento"));

        verify(glpiIntegrationService, times(1)).initSession();
    }

    @Test
    void processaGlpi_categoriaIgnoraCase_processa() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "desenvolvimento");
        configurarMocksFluxoCompleto();

        integrationService.processGlpiWebhook(payloadComCategoria("DESENVOLVIMENTO"));

        verify(glpiIntegrationService, times(1)).initSession();
    }

    @Test
    void processaGlpi_categoriaDiferente_ignoraTicket() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "Desenvolvimento");

        integrationService.processGlpiWebhook(payloadComCategoria("Suporte"));

        verifyNoInteractions(glpiIntegrationService);
    }

    @Test
    void processaGlpi_semCategoria_gatilhoCategoriaAtivo_ignoraTicket() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "Desenvolvimento");
        var item = new GlpiItem(1L, "Ticket", "Conteúdo", null, null, List.of());

        integrationService.processGlpiWebhook(new GlpiWebhookPayload("add", item));

        verifyNoInteractions(glpiIntegrationService);
    }

    // -------------------------------------------------------------------------
    // Filtragem por técnico (usa API — não o payload)
    // -------------------------------------------------------------------------

    @Test
    void processaGlpi_tecnicoWildcard_processaSempre() {
        ReflectionTestUtils.setField(integrationService, "assigneeThatSendToTaiga", "*");
        configurarMocksFluxoCompleto();

        integrationService.processGlpiWebhook(payloadSemCategoria());

        verify(glpiIntegrationService, times(1)).initSession();
    }

    @Test
    void processaGlpi_tecnicoCorrespondePelaApi_processa() {
        ReflectionTestUtils.setField(integrationService, "assigneeThatSendToTaiga", "tecnico1");
        when(glpiIntegrationService.initSession()).thenReturn("session");
        when(glpiIntegrationService.isUserAssignedToTicket(any(), eq("tecnico1"), any())).thenReturn(true);
        configurarMocksFluxoCompleto();

        integrationService.processGlpiWebhook(payloadSemCategoria());

        verify(taigaIntegrationService, times(1)).createIssueOnTaiga(any(), any(), any(), any());
    }

    @Test
    void processaGlpi_tecnicoNaoAtribuidoPelaApi_ignoraTicket() {
        ReflectionTestUtils.setField(integrationService, "assigneeThatSendToTaiga", "tecnico1");
        when(glpiIntegrationService.initSession()).thenReturn("session");
        when(glpiIntegrationService.isUserAssignedToTicket(any(), eq("tecnico1"), any())).thenReturn(false);

        integrationService.processGlpiWebhook(payloadSemCategoria());

        verify(taigaIntegrationService, never()).createIssueOnTaiga(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Idempotência
    // -------------------------------------------------------------------------

    @Test
    void processaGlpi_ticketJaIntegrado_naoReprocessa() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "*");
        var recordExistente = new GlpiPluginFieldsRecord(1L, 10L, "123", "http://taiga/issue/1");
        when(glpiIntegrationService.initSession()).thenReturn("session");
        when(glpiIntegrationService.getPluginFieldsRecord(any(), any()))
                .thenReturn(Optional.of(recordExistente));

        integrationService.processGlpiWebhook(payloadComCategoria("Qualquer"));

        verify(taigaIntegrationService, never()).authenticateInTaiga();
        verify(taigaIntegrationService, never()).createIssueOnTaiga(any(), any(), any(), any());
    }

    @Test
    void processaGlpi_recordComIdZero_reprocessa() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "*");
        var recordComIdZero = new GlpiPluginFieldsRecord(1L, 10L, "0", null);
        when(glpiIntegrationService.initSession()).thenReturn("session");
        when(glpiIntegrationService.getPluginFieldsRecord(any(), any()))
                .thenReturn(Optional.of(recordComIdZero));
        when(taigaIntegrationService.authenticateInTaiga()).thenReturn("taiga-token");
        when(projectRoutingService.resolveTaigaProjectSlugForTicket(any(), any())).thenReturn("projeto-1");
        when(taigaIntegrationService.getProjectBySlug(any(), any()))
                .thenReturn(new TaigaProjectResponse(42L, "projeto-1", "Projeto 1"));
        when(taigaIntegrationService.createIssueOnTaiga(any(), any(), any(), any()))
                .thenReturn(new TaigaIssueResponse(99L, 5L, null));
        when(taigaIntegrationService.buildTaigaIssueUrl(any(), any())).thenReturn("http://taiga/issue/5");
        when(glpiIntegrationService.getInitialStatusExternalProgress()).thenReturn("New");

        integrationService.processGlpiWebhook(payloadComCategoria("Qualquer"));

        verify(taigaIntegrationService, times(1)).createIssueOnTaiga(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Resiliência de autenticação
    // -------------------------------------------------------------------------

    @Test
    void processaGlpi_authException_retentaUmaVez() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "*");
        when(glpiIntegrationService.initSession())
                .thenThrow(new IntegrationAuthenticationException("401"))
                .thenReturn("session-novo");
        when(glpiIntegrationService.getPluginFieldsRecord(any(), any())).thenReturn(Optional.empty());
        when(taigaIntegrationService.authenticateInTaiga()).thenReturn("taiga-token");
        when(projectRoutingService.resolveTaigaProjectSlugForTicket(any(), any())).thenReturn("projeto-1");
        when(taigaIntegrationService.getProjectBySlug(any(), any()))
                .thenReturn(new TaigaProjectResponse(42L, "projeto-1", "Projeto 1"));
        when(taigaIntegrationService.createIssueOnTaiga(any(), any(), any(), any()))
                .thenReturn(new TaigaIssueResponse(99L, 5L, null));
        when(taigaIntegrationService.buildTaigaIssueUrl(any(), any())).thenReturn("http://taiga/issue/5");
        when(glpiIntegrationService.getInitialStatusExternalProgress()).thenReturn("New");

        integrationService.processGlpiWebhook(payloadComCategoria("Qualquer"));

        verify(glpiIntegrationService, times(2)).initSession();
        verify(taigaIntegrationService, times(1)).createIssueOnTaiga(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Webhook Taiga
    // -------------------------------------------------------------------------

    @Test
    void processaTaiga_acaoCreate_ignorada() {
        var payload = new TaigaWebhookPayload("create", "issue",
                new TaigaIssueData(1L, 1L, "Issue", "Desc",
                        new TaigaStatusData(1L, "New"), null, null, null), null);

        integrationService.processTaigaWebhook(payload);

        verifyNoInteractions(glpiIntegrationService);
    }

    @Test
    void processaTaiga_historiaSemIssueDeOrigem_ignorada() {
        var payload = new TaigaWebhookPayload("change", "userstory",
                new TaigaIssueData(1L, 1L, "US", "Desc",
                        new TaigaStatusData(1L, "In Progress"), null, null, null), null);

        integrationService.processTaigaWebhook(payload);

        verifyNoInteractions(glpiIntegrationService);
    }

    @Test
    void processaTaiga_ticketGlpiNaoEncontrado_naoAtualiza() {
        when(glpiIntegrationService.initSession()).thenReturn("session");
        when(glpiIntegrationService.getTicketByIdTaiga(any(), any())).thenReturn(Optional.empty());
        var payload = new TaigaWebhookPayload("change", "issue",
                new TaigaIssueData(99L, 5L, "Issue", "Desc",
                        new TaigaStatusData(2L, "In Progress"), null, null, null), null);

        integrationService.processTaigaWebhook(payload);

        verify(glpiIntegrationService, never()).syncExternalProgress(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GlpiWebhookPayload payloadComCategoria(String nomeCategoria) {
        var item = new GlpiItem(1L, "Ticket Teste", "Conteúdo",
                new GlpiCategory(10L, nomeCategoria), null, List.of());
        return new GlpiWebhookPayload("add", item);
    }

    private GlpiWebhookPayload payloadSemCategoria() {
        var item = new GlpiItem(1L, "Ticket Teste", "Conteúdo", null, null, List.of());
        return new GlpiWebhookPayload("add", item);
    }

    private void configurarMocksFluxoCompleto() {
        when(glpiIntegrationService.initSession()).thenReturn("session-token");
        when(glpiIntegrationService.getPluginFieldsRecord(any(), any())).thenReturn(Optional.empty());
        when(taigaIntegrationService.authenticateInTaiga()).thenReturn("taiga-token");
        when(projectRoutingService.resolveTaigaProjectSlugForTicket(any(), any())).thenReturn("projeto-1");
        when(taigaIntegrationService.getProjectBySlug(any(), any()))
                .thenReturn(new TaigaProjectResponse(42L, "projeto-1", "Projeto 1"));
        when(taigaIntegrationService.createIssueOnTaiga(any(), any(), any(), any()))
                .thenReturn(new TaigaIssueResponse(99L, 5L, null));
        when(taigaIntegrationService.buildTaigaIssueUrl(any(), any())).thenReturn("http://taiga/issue/5");
        when(glpiIntegrationService.getInitialStatusExternalProgress()).thenReturn("New");
    }
}