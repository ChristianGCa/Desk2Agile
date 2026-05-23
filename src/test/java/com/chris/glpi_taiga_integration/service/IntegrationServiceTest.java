package com.chris.glpi_taiga_integration.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chris.glpi_taiga_integration.dto.GlpiCategory;
import com.chris.glpi_taiga_integration.dto.GlpiItem;
import com.chris.glpi_taiga_integration.dto.GlpiPluginFieldsRecord;
import com.chris.glpi_taiga_integration.dto.GlpiTeamMember;
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

    @Mock
    private TaigaIntegrationService taigaIntegrationService;

    @Mock
    private GlpiIntegrationService glpiIntegrationService;

    @Mock
    private ProjectRoutingService projectRoutingService;

    // FIX: FailureLogService é exigido pelo construtor — sem ele, Mockito usa Objenesis
    // e o array locks[] nunca é inicializado → NullPointerException
    @Mock
    private FailureLogService failureLogService;

    @InjectMocks
    private IntegrationService integrationService;

    @BeforeEach
    void setUp() {
        // Ambos os gatilhos desligados por padrão; cada teste ativa o que precisa
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "");
        ReflectionTestUtils.setField(integrationService, "assigneeThatSendToTaiga", "");
        // FIX: @Value não é processado em testes Mockito puros — maxAuthRetries fica 0 (default de int)
        // Definir explicitamente para corresponder ao default da aplicação
        ReflectionTestUtils.setField(integrationService, "maxAuthRetries", 1);
    }

    @Test
    void processaGlpi_ambosGatilhosDesligados_ignoraTicket() {
        var payload = payloadComCategoria("Desenvolvimento");

        integrationService.processGlpiWebhook(payload);

        verifyNoInteractions(glpiIntegrationService);
        verifyNoInteractions(taigaIntegrationService);
    }

    @Test
    void processaGlpi_categoriaWildcard_processaQualquerCategoria() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "*");
        configurarMocksParaFluxoCompleto();
        var payload = payloadComCategoria("Qualquer Coisa");

        integrationService.processGlpiWebhook(payload);

        verify(glpiIntegrationService, times(1)).initSession();
    }

    @Test
    void processaGlpi_categoriaEspecificaCorresponde_processa() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "Desenvolvimento");
        configurarMocksParaFluxoCompleto();
        var payload = payloadComCategoria("Desenvolvimento");

        integrationService.processGlpiWebhook(payload);

        verify(glpiIntegrationService, times(1)).initSession();
    }

    @Test
    void processaGlpi_categoriaEspecificaIgnoraCase_processa() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "desenvolvimento");
        configurarMocksParaFluxoCompleto();
        var payload = payloadComCategoria("DESENVOLVIMENTO");

        integrationService.processGlpiWebhook(payload);

        verify(glpiIntegrationService, times(1)).initSession();
    }

    @Test
    void processaGlpi_categoriaDiferente_ignoraTicket() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "Desenvolvimento");
        var payload = payloadComCategoria("Suporte");

        integrationService.processGlpiWebhook(payload);

        verifyNoInteractions(glpiIntegrationService);
    }

    @Test
    void processaGlpi_semCategoria_gatilhoCategoriaAtivo_ignoraTicket() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "Desenvolvimento");
        var item = new GlpiItem(1L, "Ticket", "Conteúdo", null, null, List.of());
        var payload = new GlpiWebhookPayload("add", item);

        integrationService.processGlpiWebhook(payload);

        verifyNoInteractions(glpiIntegrationService);
    }

    @Test
    void processaGlpi_tecnicoCorresponde_processa() {
        ReflectionTestUtils.setField(integrationService, "assigneeThatSendToTaiga", "tecnico1");
        configurarMocksParaFluxoCompleto();
        var payload = payloadComTecnico("tecnico1");

        integrationService.processGlpiWebhook(payload);

        verify(glpiIntegrationService, times(1)).initSession();
    }

    @Test
    void processaGlpi_tecnicoWildcard_processaSempre() {
        ReflectionTestUtils.setField(integrationService, "assigneeThatSendToTaiga", "*");
        configurarMocksParaFluxoCompleto();
        var payload = payloadComTecnico("qualquer-tecnico");

        integrationService.processGlpiWebhook(payload);

        verify(glpiIntegrationService, times(1)).initSession();
    }

    @Test
    void processaGlpi_tecnicoDiferente_ignoraTicket() {
        ReflectionTestUtils.setField(integrationService, "assigneeThatSendToTaiga", "tecnico1");
        var payload = payloadComTecnico("tecnico2");

        integrationService.processGlpiWebhook(payload);

        verifyNoInteractions(glpiIntegrationService);
    }

    @Test
    void processaGlpi_membroSemRoleAssigned_ignoraTicket() {
        ReflectionTestUtils.setField(integrationService, "assigneeThatSendToTaiga", "tecnico1");
        var membro = new GlpiTeamMember("observer", "tecnico1", null, null, null);
        var item = new GlpiItem(1L, "Ticket", "Conteúdo", null, null, List.of(membro));
        var payload = new GlpiWebhookPayload("add", item);

        integrationService.processGlpiWebhook(payload);

        verifyNoInteractions(glpiIntegrationService);
    }

    @Test
    void processaGlpi_ticketJaPossuiIssueTaiga_naoReprocessa() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "*");
        var recordExistente = new GlpiPluginFieldsRecord(1L, 10L, "123", "http://taiga/issue/1");
        when(glpiIntegrationService.initSession()).thenReturn("session");
        when(glpiIntegrationService.getPluginFieldsRecord(any(), any()))
                .thenReturn(Optional.of(recordExistente));
        var payload = payloadComCategoria("Qualquer");

        integrationService.processGlpiWebhook(payload);

        verify(taigaIntegrationService, never()).authenticateInTaiga();
        verify(taigaIntegrationService, never()).createIssueOnTaiga(any(), any(), any(), any());
    }

    @Test
    void processaGlpi_recordComIdTaigaZero_reprocessa() {
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

        var payload = payloadComCategoria("Qualquer");
        integrationService.processGlpiWebhook(payload);

        verify(taigaIntegrationService, times(1)).createIssueOnTaiga(any(), any(), any(), any());
    }

    @Test
    void processaGlpi_authException_retentaUmaVez() {
        ReflectionTestUtils.setField(integrationService, "categoryThatSendToTaiga", "*");

        // Primeira chamada lança exceção; segunda retorna token válido
        when(glpiIntegrationService.initSession())
                .thenThrow(new IntegrationAuthenticationException("401 Unauthorized"))
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

        // initSession chamado duas vezes: falha na 1ª tentativa, sucesso na 2ª
        verify(glpiIntegrationService, times(2)).initSession();
        verify(taigaIntegrationService, times(1)).createIssueOnTaiga(any(), any(), any(), any());
    }

    @Test
    void processaTaiga_acaoCreate_ignorada() {
        var payload = new TaigaWebhookPayload(
                "create", "issue",
                new TaigaIssueData(1L, 1L, "Issue", "Desc", new TaigaStatusData(1L, "New"), null, null, null), null);

        integrationService.processTaigaWebhook(payload);

        verifyNoInteractions(glpiIntegrationService);
    }

    @Test
    void processaTaiga_tipoNaoIssue_ignorado() {
        var payload = new TaigaWebhookPayload(
                "change", "userstory",
                new TaigaIssueData(1L, 1L, "US", "Desc", new TaigaStatusData(1L, "In Progress"), null, null, null), null);

        integrationService.processTaigaWebhook(payload);

        verifyNoInteractions(glpiIntegrationService);
    }

    @Test
    void processaTaiga_ticketGlpiNaoEncontrado_naoAtualiza() {
        when(glpiIntegrationService.initSession()).thenReturn("session");
        when(glpiIntegrationService.getTicketByIdTaiga(any(), any())).thenReturn(Optional.empty());

        var payload = new TaigaWebhookPayload(
                "change", "issue",
                new TaigaIssueData(99L, 5L, "Issue", "Desc", new TaigaStatusData(2L, "In Progress"), null, null, null), null);

        integrationService.processTaigaWebhook(payload);

        verify(glpiIntegrationService, never()).syncExternalProgress(any(), any(), any(), any());
    }

    private GlpiWebhookPayload payloadComCategoria(String nomeCategoria) {
        var item = new GlpiItem(1L, "Ticket Teste", "Conteúdo do ticket",
                new GlpiCategory(10L, nomeCategoria), null, List.of());
        return new GlpiWebhookPayload("add", item);
    }

    private GlpiWebhookPayload payloadComTecnico(String login) {
        var membro = new GlpiTeamMember("assigned", login, null, null, null);
        var item = new GlpiItem(1L, "Ticket Teste", "Conteúdo", null, null, List.of(membro));
        return new GlpiWebhookPayload("add", item);
    }

    private void configurarMocksParaFluxoCompleto() {
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