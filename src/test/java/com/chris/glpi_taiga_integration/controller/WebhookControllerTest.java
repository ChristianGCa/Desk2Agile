package com.chris.glpi_taiga_integration.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chris.glpi_taiga_integration.config.IpAllowlistFilter;
import com.chris.glpi_taiga_integration.config.WebhookSecurityProperties;
import com.chris.glpi_taiga_integration.service.IntegrationService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = WebhookController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = IpAllowlistFilter.class))
class WebhookControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IntegrationService integrationService;

    @MockitoBean
    private WebhookSecurityProperties webhookSecurityProperties;

    @MockitoBean(name = "webhookExecutor")
    private ThreadPoolTaskExecutor webhookExecutor;

    @BeforeEach
    void setUp() {
        // Autenticação desligada por padrão; testes de auth configuram explicitamente
        when(webhookSecurityProperties.getGlpiToken()).thenReturn("");
        when(webhookSecurityProperties.getTaigaSecret()).thenReturn("");

        // Executor roda a task na própria thread de teste (comportamento síncrono)
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(webhookExecutor).execute(any(Runnable.class));
    }

    // POST /api/webhook/glpi — validação de payload

    @Test
    void glpi_payloadSemItem_retorna400() throws Exception {
        mvc.perform(post("/api/webhook/glpi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"event":"add"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Payload inválido: item ausente."));
    }

    @Test
    void glpi_payloadValido_retorna202() throws Exception {
        doNothing().when(integrationService).processGlpiWebhook(any());

        mvc.perform(post("/api/webhook/glpi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "event": "add",
                          "item": {"id": 1, "name": "Ticket Teste", "content": "Descrição"}
                        }
                        """))
                .andExpect(status().isAccepted())
                .andExpect(content().string("Webhook GLPI recebido."));

        verify(integrationService).processGlpiWebhook(any());
    }

    @Test
    void glpi_aceitaContentTypeOctetStream() throws Exception {
        doNothing().when(integrationService).processGlpiWebhook(any());

        mvc.perform(post("/api/webhook/glpi")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("""
                        {
                          "event": "add",
                          "item": {"id": 1, "name": "Ticket", "content": "Desc"}
                        }
                        """))
                .andExpect(status().isAccepted());
    }

    // POST /api/webhook/glpi — autenticação

    @Test
    void glpi_semToken_configurado_retorna401() throws Exception {
        when(webhookSecurityProperties.getGlpiToken()).thenReturn("secret-token");

        mvc.perform(post("/api/webhook/glpi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "event": "add",
                          "item": {"id": 1, "name": "Ticket", "content": "Desc"}
                        }
                        """))
                .andExpect(status().isUnauthorized());

        verify(integrationService, never()).processGlpiWebhook(any());
    }

    @Test
    void glpi_tokenErrado_retorna401() throws Exception {
        when(webhookSecurityProperties.getGlpiToken()).thenReturn("secret-token");

        mvc.perform(post("/api/webhook/glpi")
                        .header("Authorization", "Bearer token-errado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "event": "add",
                          "item": {"id": 1, "name": "Ticket", "content": "Desc"}
                        }
                        """))
                .andExpect(status().isUnauthorized());

        verify(integrationService, never()).processGlpiWebhook(any());
    }

    @Test
    void glpi_tokenCorreto_retorna202() throws Exception {
        when(webhookSecurityProperties.getGlpiToken()).thenReturn("secret-token");
        doNothing().when(integrationService).processGlpiWebhook(any());

        mvc.perform(post("/api/webhook/glpi")
                        .header("Authorization", "Bearer secret-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "event": "add",
                          "item": {"id": 1, "name": "Ticket", "content": "Desc"}
                        }
                        """))
                .andExpect(status().isAccepted());
    }

    // POST /api/webhook/glpi — fila cheia

    @Test
    void glpi_filaCheia_retorna503() throws Exception {
        doAnswer(inv -> { throw new java.util.concurrent.RejectedExecutionException(); })
                .when(webhookExecutor).execute(any(Runnable.class));

        mvc.perform(post("/api/webhook/glpi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "event": "add",
                          "item": {"id": 1, "name": "Ticket", "content": "Desc"}
                        }
                        """))
                .andExpect(status().isServiceUnavailable());
    }

    // POST /api/webhook/taiga — validação de payload

    @Test
    void taiga_payloadSemData_retorna400() throws Exception {
        mvc.perform(post("/api/webhook/taiga")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"action": "change", "type": "issue"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Payload inválido: data ausente."));
    }

    @Test
    void taiga_tipoNaoTratado_retorna200ComMensagemIgnorado() throws Exception {
        mvc.perform(post("/api/webhook/taiga")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "action": "change",
                          "type": "epic",
                          "data": {"id": 1, "ref": 1, "status": {"id": 1, "name": "In Progress"}}
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Evento ignorado")));

        verify(integrationService, never()).processTaigaWebhook(any());
    }

    @Test
    void taiga_payloadValido_retorna202() throws Exception {
        doNothing().when(integrationService).processTaigaWebhook(any());

        mvc.perform(post("/api/webhook/taiga")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "action": "change",
                          "type": "issue",
                          "data": {"id": 1, "ref": 1, "status": {"id": 2, "name": "In Progress"}}
                        }
                        """))
                .andExpect(status().isAccepted())
                .andExpect(content().string("Webhook Taiga recebido."));

        verify(integrationService).processTaigaWebhook(any());
    }

    @Test
    void taiga_filaCheia_retorna503() throws Exception {
        doAnswer(inv -> { throw new RejectedExecutionException(); })
                .when(webhookExecutor).execute(any(Runnable.class));

        mvc.perform(post("/api/webhook/taiga")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "action": "change",
                          "type": "issue",
                          "data": {"id": 1, "ref": 1, "status": {"id": 2, "name": "Done"}}
                        }
                        """))
                .andExpect(status().isServiceUnavailable());
    }
}