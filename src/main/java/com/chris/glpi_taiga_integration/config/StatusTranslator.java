package com.chris.glpi_taiga_integration.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Traduz status do Taiga (inglês) para o texto que será exibido no GLPI.
 * Para sobrescrever ou adicionar entradas, configure {@code glpi.status-map} no application.yaml.
 * Entradas no yaml têm precedência sobre os defaults.
 */
@Getter
@Component
@ConfigurationProperties(prefix = "glpi")
public class StatusTranslator {

    private static final Logger log = LoggerFactory.getLogger(StatusTranslator.class);
    private static final Map<String, String> DEFAULTS = Map.of(
            "new",              "Novo",
            "in progress",      "Em andamento",
            "ready for test",   "Pronto para teste",
            "done",             "Concluído",
            "archived",         "Arquivado",
            "closed",           "Fechado",
            "needs info",       "Aguardando informação",
            "rejected",         "Rejeitado",
            "postponed",        "Adiado",
            "ready",            "Preparado"
    );

    /**
     * Sobrescritas via {@code glpi.status-map} no yaml.
     * Só precisam ser declaradas se o usuário quiser mudar um default ou
     * adicionar status customizados do Taiga.
     */
    private final List<StatusMapEntry> statusMap = new ArrayList<>();
    public String translate(String taigaStatus) {
        if (taigaStatus == null || taigaStatus.isBlank()) return taigaStatus;

        // yaml overrides têm precedência
        String fromYaml = statusMap.stream()
                .filter(e -> e.getTaiga().equalsIgnoreCase(taigaStatus))
                .map(StatusMapEntry::getGlpi)
                .findFirst()
                .orElse(null);

        if (fromYaml != null) return fromYaml;

        // Fallback para defaults embutidos
        String fromDefault = DEFAULTS.get(taigaStatus.toLowerCase());
        if (fromDefault != null) return fromDefault;

        log.warn("STATUS MAP - Status '{}' não mapeado. Gravando o valor original.", taigaStatus);
        return taigaStatus;
    }
}