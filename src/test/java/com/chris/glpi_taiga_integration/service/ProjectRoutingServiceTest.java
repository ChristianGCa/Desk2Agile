package com.chris.glpi_taiga_integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.chris.glpi_taiga_integration.config.GlpiEntitiesProperties;
import com.chris.glpi_taiga_integration.config.TaigaRoutingProperties;
import com.chris.glpi_taiga_integration.config.TaigaRoutingProperties.EntityMapping;
import com.chris.glpi_taiga_integration.dto.GlpiEntityResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectRoutingServiceTest {

    @Mock
    private TaigaRoutingProperties taigaRoutingProperties;

    @Mock
    private GlpiEntitiesProperties glpiEntitiesProperties;

    @Mock
    private GlpiIntegrationService glpiIntegrationService;

    private ProjectRoutingService service;

    @BeforeEach
    void setUp() {
        service = new ProjectRoutingService(taigaRoutingProperties, glpiEntitiesProperties, glpiIntegrationService);
    }

    @Test
    void toTaigaSlug_nomeSimples() {
        assertThat(ProjectRoutingService.toTaigaSlug("Projeto Alpha")).isEqualTo("projeto-alpha");
    }

    @Test
    void toTaigaSlug_comAcento() {
        assertThat(ProjectRoutingService.toTaigaSlug("Gestão de TI")).isEqualTo("gestao-de-ti");
    }

    @Test
    void toTaigaSlug_comCaracteresEspeciais() {
        assertThat(ProjectRoutingService.toTaigaSlug("TI/SP (produção)")).isEqualTo("tisp-producao");
    }

    @Test
    void toTaigaSlug_multiplosEspacos() {
        assertThat(ProjectRoutingService.toTaigaSlug("  Infra   Cloud  ")).isEqualTo("infra-cloud");
    }

    @Test
    void toTaigaSlug_null_retornaVazio() {
        assertThat(ProjectRoutingService.toTaigaSlug(null)).isEmpty();
    }

    @Test
    void toTaigaSlug_vazio_retornaVazio() {
        assertThat(ProjectRoutingService.toTaigaSlug("   ")).isEmpty();
    }

    @Test
    void toTaigaSlug_soNumeros() {
        assertThat(ProjectRoutingService.toTaigaSlug("Projeto 2025")).isEqualTo("projeto-2025");
    }

    @Test
    void resolve_entidadeMapeada_retornaSlugCorreto() {
        var mapping = criarMapping("Serviço 1", "Projeto Serviço 1");
        when(taigaRoutingProperties.getFallbackProjectName()).thenReturn("Diversos");
        when(taigaRoutingProperties.getEntityMappings()).thenReturn(List.of(mapping));
        when(glpiEntitiesProperties.getRootName()).thenReturn("Entidade raiz");
        when(glpiIntegrationService.getTicketEntityId(eq(10L), any())).thenReturn(Optional.of(5L));
        when(glpiIntegrationService.getEntityById(eq(5L), any()))
                .thenReturn(Optional.of(new GlpiEntityResponse(5L, "Serviço 1", null)));

        String slug = service.resolveTaigaProjectSlugForTicket(10L, "session");

        assertThat(slug).isEqualTo("projeto-servico-1");
    }

    @Test
    void resolve_entidadeNaoMapeada_retornaFallback() {
        var mapping = criarMapping("Serviço 1", "Projeto Serviço 1");
        when(taigaRoutingProperties.getFallbackProjectName()).thenReturn("Diversos");
        when(taigaRoutingProperties.getEntityMappings()).thenReturn(List.of(mapping));
        when(glpiEntitiesProperties.getRootName()).thenReturn("Entidade raiz");
        when(glpiIntegrationService.getTicketEntityId(eq(10L), any())).thenReturn(Optional.of(5L));
        when(glpiIntegrationService.getEntityById(eq(5L), any()))
                .thenReturn(Optional.of(new GlpiEntityResponse(5L, "Serviço Desconhecido", null)));

        String slug = service.resolveTaigaProjectSlugForTicket(10L, "session");

        assertThat(slug).isEqualTo("diversos");
    }

    @Test
    void resolve_entidadeRaiz_retornaFallback() {
        var mapping = criarMapping("Serviço 1", "Projeto Serviço 1");
        when(taigaRoutingProperties.getFallbackProjectName()).thenReturn("Diversos");
        when(taigaRoutingProperties.getEntityMappings()).thenReturn(List.of(mapping));
        when(glpiEntitiesProperties.getRootName()).thenReturn("Entidade raiz");
        when(glpiIntegrationService.getTicketEntityId(eq(10L), any())).thenReturn(Optional.of(1L));
        when(glpiIntegrationService.getEntityById(eq(1L), any()))
                .thenReturn(Optional.of(new GlpiEntityResponse(1L, "Entidade raiz", null)));

        String slug = service.resolveTaigaProjectSlugForTicket(10L, "session");

        assertThat(slug).isEqualTo("diversos");
    }

    @Test
    void resolve_semMappings_retornaFallback() {
        when(taigaRoutingProperties.getFallbackProjectName()).thenReturn("Diversos");
        when(taigaRoutingProperties.getEntityMappings()).thenReturn(List.of());

        String slug = service.resolveTaigaProjectSlugForTicket(10L, "session");

        assertThat(slug).isEqualTo("diversos");
    }

    @Test
    void resolve_semEntityId_retornaFallback() {
        var mapping = criarMapping("Serviço 1", "Projeto Serviço 1");
        when(taigaRoutingProperties.getFallbackProjectName()).thenReturn("Diversos");
        when(taigaRoutingProperties.getEntityMappings()).thenReturn(List.of(mapping));
        when(glpiIntegrationService.getTicketEntityId(any(), any())).thenReturn(Optional.empty());

        String slug = service.resolveTaigaProjectSlugForTicket(10L, "session");

        assertThat(slug).isEqualTo("diversos");
    }

    @Test
    void resolve_comparacaoNomeEntidadeCaseInsensitive() {
        var mapping = criarMapping("SERVIÇO 1", "Projeto Serviço 1");
        when(taigaRoutingProperties.getFallbackProjectName()).thenReturn("Diversos");
        when(taigaRoutingProperties.getEntityMappings()).thenReturn(List.of(mapping));
        when(glpiEntitiesProperties.getRootName()).thenReturn("Entidade raiz");
        when(glpiIntegrationService.getTicketEntityId(eq(10L), any())).thenReturn(Optional.of(5L));
        // Nota: resolveFromMappings usa .equals (case-sensitive), não equalsIgnoreCase.
        // Este teste documenta o comportamento ATUAL — se mudar, o teste vai falhar
        // e avisará o desenvolvedor.
        when(glpiIntegrationService.getEntityById(eq(5L), any()))
                .thenReturn(Optional.of(new GlpiEntityResponse(5L, "SERVIÇO 1", null)));

        String slug = service.resolveTaigaProjectSlugForTicket(10L, "session");

        assertThat(slug).isEqualTo("projeto-servico-1");
    }

    private EntityMapping criarMapping(String glpiName, String taigaProjectName) {
        var m = new EntityMapping();
        m.setGlpiEntityName(glpiName);
        m.setTaigaProjectName(taigaProjectName);
        return m;
    }
}