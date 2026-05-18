package com.chris.glpi_taiga_integration.config;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "taiga.routing")
public class TaigaRoutingProperties {

    /**
     * Slug do projeto Taiga usado quando a entidade do GLPI não estiver mapeada
     * ou quando o ticket estiver na entidade raiz.
     */
    private String fallbackProjectName = "Diversos";

    /**
     * Mapa: nome da entidade no GLPI -> slug do projeto no Taiga.
     *
     * <p>
     * Preferir {@link #entityMappings} para evitar problemas com chaves
     * contendo espaços em ambientes que usam .env/variáveis de ambiente.
     */
    private Map<String, String> entityProjectSlugs = new HashMap<>();

    /**
     * Lista explícita: nome da entidade no GLPI -> nome do projeto no Taiga. O
     * slug é gerado automaticamente a partir do nome, seguindo a mesma lógica
     * do Taiga.
     */
    private List<EntityMapping> entityMappings = new ArrayList<>();

    @Setter
    @Getter
    public static class EntityMapping {

        private String glpiEntityName;
        private String taigaProjectName;

    }
}
