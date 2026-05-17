package com.chris.glpi_taiga_integration.config;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

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

    public String getFallbackProjectName() {
        return fallbackProjectName;
    }

    public void setFallbackProjectName(String fallbackProjectName) {
        this.fallbackProjectName = fallbackProjectName;
    }

    public Map<String, String> getEntityProjectSlugs() {
        return entityProjectSlugs;
    }

    public void setEntityProjectSlugs(Map<String, String> entityProjectSlugs) {
        this.entityProjectSlugs = entityProjectSlugs;
    }

    public List<EntityMapping> getEntityMappings() {
        return entityMappings;
    }

    public void setEntityMappings(List<EntityMapping> entityMappings) {
        this.entityMappings = entityMappings;
    }

    public static class EntityMapping {

        private String glpiEntityName;
        private String taigaProjectName;

        public String getGlpiEntityName() {
            return glpiEntityName;
        }

        public void setGlpiEntityName(String glpiEntityName) {
            this.glpiEntityName = glpiEntityName;
        }

        public String getTaigaProjectName() {
            return taigaProjectName;
        }

        public void setTaigaProjectName(String taigaProjectName) {
            this.taigaProjectName = taigaProjectName;
        }
    }
}
