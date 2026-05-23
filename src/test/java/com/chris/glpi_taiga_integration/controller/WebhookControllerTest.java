package com.chris.glpi_taiga_integration.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chris.glpi_taiga_integration.config.IpAllowlistFilter;
import com.chris.glpi_taiga_integration.config.WebhookSecurityProperties;
import com.chris.glpi_taiga_integration.exception.GlpiPluginFieldsException;
import com.chris.glpi_taiga_integration.service.FailureLogService;
import com.chris.glpi_taiga_integration.service.IntegrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = WebhookController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = IpAllowlistFilter.class))
class WebhookControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private IntegrationService integrationService;

    // FIX: beans não-web não são carregados pelo @WebMvcTest — precisam de @MockBean
    @MockBean
    private FailureLogService failureLogService;

    @MockBean
    private WebhookSecurityProperties webhookSecurityProperties;

    // POST /api/webhook/glpi
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
    void glpi_payloadVazio_retorna400() throws Exception {
        mvc.perform(post("/api/webhook/glpi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void glpi_payloadValido_retorna200() throws Exception {
        doNothing().when(integrationService).processGlpiWebhook(any());

        mvc.perform(post("/api/webhook/glpi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "event": "add",
                          "item": {"id": 1, "name": "Ticket Teste", "content": "Descrição"}
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(content().string("Webhook GLPI processado com sucesso."));

        verify(integrationService).processGlpiWebhook(any());
    }

    @Test
    void glpi_erroPluginFields_retorna400() throws Exception {
        doThrow(new GlpiPluginFieldsException("Bloco não encontrado", null))
                .when(integrationService).processGlpiWebhook(any());

        mvc.perform(post("/api/webhook/glpi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "event": "add",
                          "item": {"id": 1, "name": "Ticket", "content": "Desc"}
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Bloco não encontrado")));
    }

    @Test
    void glpi_erroInesperado_retorna500() throws Exception {
        doThrow(new RuntimeException("Conexão recusada"))
                .when(integrationService).processGlpiWebhook(any());

        mvc.perform(post("/api/webhook/glpi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "event": "add",
                          "item": {"id": 1, "name": "Ticket", "content": "Desc"}
                        }
                        """))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("Erro interno ao processar webhook GLPI."));
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
                .andExpect(status().isOk());
    }

    // POST /api/webhook/taiga
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

    // FIX: o controller aceita "issue" e "userstory" — usar tipo realmente ignorado (ex: "epic")
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
    void taiga_payloadValido_retorna200() throws Exception {
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
                .andExpect(status().isOk())
                .andExpect(content().string("Webhook Taiga processado com sucesso."));

        verify(integrationService).processTaigaWebhook(any());
    }

    @Test
    void taiga_erroPluginFields_retorna400() throws Exception {
        doThrow(new GlpiPluginFieldsException("Campo ausente", null))
                .when(integrationService).processTaigaWebhook(any());

        mvc.perform(post("/api/webhook/taiga")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "action": "change",
                          "type": "issue",
                          "data": {"id": 1, "ref": 1, "status": {"id": 2, "name": "Done"}}
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Campo ausente")));
    }

    @Test
    void taiga_erroInesperado_retorna500() throws Exception {
        doThrow(new RuntimeException("Timeout"))
                .when(integrationService).processTaigaWebhook(any());

        mvc.perform(post("/api/webhook/taiga")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "action": "change",
                          "type": "issue",
                          "data": {"id": 1, "ref": 1, "status": {"id": 2, "name": "Done"}}
                        }
                        """))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("Erro interno ao processar webhook Taiga."));
    }
}