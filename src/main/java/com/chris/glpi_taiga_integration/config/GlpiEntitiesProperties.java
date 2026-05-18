package com.chris.glpi_taiga_integration.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "glpi.entities")
public class GlpiEntitiesProperties {

    /**
     * Nome da entidade raiz no GLPI (exibida na UI / API).
     */
    private String rootName = "Entidade raiz";

}
