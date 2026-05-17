package com.chris.glpi_taiga_integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "glpi.entities")
public class GlpiEntitiesProperties {

    /**
     * Nome da entidade raiz no GLPI (exibida na UI / API).
     */
    private String rootName = "Entidade raiz";

    public String getRootName() {
        return rootName;
    }

    public void setRootName(String rootName) {
        this.rootName = rootName;
    }
}
