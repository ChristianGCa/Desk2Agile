package com.chris.glpi_taiga_integration.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Traduz nomes de status vindos do Taiga para um valor personalizado
 * usando um dicionário definido em {@code glpi.status-map} no application.yaml.
 * Isso serve para poder colocar um texto mais compreensível já que o valor
 * bruto que o Taiga envia está em inglês.
 * Ex.: New - Novo, In progress - Em progresso.
 *
 * <p>Fallback: se o status recebido não estiver mapeado, o valor original vindo do Taiga é usado.
 */
@Getter
@Component
@ConfigurationProperties(prefix = "glpi")
public class StatusTranslator {

    private static final Logger log = LoggerFactory.getLogger(StatusTranslator.class);

    private final List<StatusMapEntry> statusMap = new ArrayList<>();

    public String translate(String taigaStatus) {
        if (taigaStatus == null || taigaStatus.isBlank()) return taigaStatus;

        return statusMap.stream()
                .filter(e -> e.getTaiga().equalsIgnoreCase(taigaStatus))
                .map(StatusMapEntry::getGlpi)
                .findFirst()
                .orElseGet(() -> {
                    log.warn("STATUS MAP - Status '{}' não mapeado em glpi.status-map. "
                            + "Gravando o valor original.", taigaStatus);
                    return taigaStatus;
                });
    }
}