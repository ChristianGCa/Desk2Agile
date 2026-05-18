package com.chris.glpi_taiga_integration.config;

import jakarta.annotation.PostConstruct;
import java.util.Locale;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mapeamento das propriedades de configuração do plugin de campos customizados do GLPI.
 * Define os nomes dos blocos e campos tanto no escopo público quanto privado.
 */
@Getter
@ConfigurationProperties(prefix = "glpi.plugin-fields")
public class GlpiPluginFieldsProperties {

    @Setter private String privateTicketStatusBlockName;
    @Setter private String publicTicketStatusBlockName;
    @Setter private String statusInicial = "Em andamento";

    private final PrivateFields privateFields = new PrivateFields();
    private final PublicFields publicFields = new PublicFields();

    /**
     * Valida se todas as configurações obrigatórias foram injetadas corretamente
     * a partir dos arquivos de propriedade (application.yml/properties).
     *
     * @throws IllegalStateException Se qualquer propriedade essencial estiver em branco.
     */
    @PostConstruct
    void validate() {
        requireNonBlank(privateTicketStatusBlockName, "glpi.plugin-fields.private-ticket-status-block-name");
        requireNonBlank(publicTicketStatusBlockName, "glpi.plugin-fields.public-ticket-status-block-name");
        requireNonBlank(statusInicial, "glpi.plugin-fields.status-inicial");

        requireNonBlank(privateFields.getIdTaiga(), "glpi.plugin-fields.private-fields.id-taiga");
        requireNonBlank(privateFields.getLinkTaiga(), "glpi.plugin-fields.private-fields.link-taiga");
        requireNonBlank(publicFields.getStatusChamado(), "glpi.plugin-fields.public-fields.status-chamado");
        requireNonBlank(publicFields.getDataPrevista(), "glpi.plugin-fields.public-fields.data-prevista");
    }

    private static void requireNonBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Configuração obrigatória ausente: " + propertyName);
        }
    }

    /** Classe aninhada para os campos do bloco privado do GLPI. */
    @Data
    public static class PrivateFields {
        private String idTaiga = "ID Taiga";
        private String linkTaiga = "Link Taiga";
    }

    /** Classe aninhada para os campos do bloco público do GLPI. */
    @Data
    public static class PublicFields {
        private String statusChamado = "Status do chamado";
        private String dataPrevista = "Data prevista";
    }

    /** Bloco "Taiga" → campo ID Taiga. Ex: "ID Taiga" → "idtaigafield" */
    public String privateIdTaigaApiField() {
        return toGlpiApiFieldName(privateFields.getIdTaiga());
    }

    /** Bloco "Taiga" → campo Link Taiga. Ex: "Link Taiga" → "linktaigafield" */
    public String privateLinkTaigaApiField() {
        return toGlpiApiFieldName(privateFields.getLinkTaiga());
    }

    /** Bloco "Progresso do chamado" → campo Status. Ex: "Status do chamado" → "statusdochamadofield" */
    public String publicStatusChamadoApiField() {
        return toGlpiApiFieldName(publicFields.getStatusChamado());
    }

    /** Bloco "Progresso do chamado" → campo Data prevista. Ex: "Data prevista" → "dataprevistafield" */
    public String publicDataPrevistaApiField() {
        return toGlpiApiFieldName(publicFields.getDataPrevista());
    }

    private static String toGlpiApiFieldName(String configuredFieldName) {
        String sanitizedInput = configuredFieldName == null ? "" : configuredFieldName.trim();
        if (sanitizedInput.isEmpty()) {
            return sanitizedInput;
        }

        String normalized = sanitizedInput
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");

        if (normalized.isEmpty()) {
            return normalized;
        }

        if (normalized.endsWith("field")) {
            return normalized;
        }

        return normalized + "field";
    }
}