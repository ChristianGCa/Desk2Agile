package com.chris.glpi_taiga_integration.config;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "glpi.plugin-fields")
public class GlpiPluginFieldsProperties {

    private String privateTicketStatusBlockName;
    private String publicTicketStatusBlockName;
    private String statusInicial = "Em andamento";

    private final PrivateFields privateFields = new PrivateFields();
    private final PublicFields publicFields = new PublicFields();

    @PostConstruct
    void validate() {
        requireNonBlank(privateTicketStatusBlockName, "glpi.plugin-fields.private-ticket-status-block-name");
        requireNonBlank(publicTicketStatusBlockName, "glpi.plugin-fields.public-ticket-status-block-name");
        requireNonBlank(statusInicial, "glpi.plugin-fields.status-inicial");

        requireNonBlank(privateFields.idTaiga, "glpi.plugin-fields.private-fields.id-taiga");
        requireNonBlank(privateFields.linkTaiga, "glpi.plugin-fields.private-fields.link-taiga");
        requireNonBlank(publicFields.statusChamado, "glpi.plugin-fields.public-fields.status-chamado");
        requireNonBlank(publicFields.dataPrevista, "glpi.plugin-fields.public-fields.data-prevista");
    }

    private static void requireNonBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Configuração obrigatória ausente: " + propertyName);
        }
    }

    public String getPrivateTicketStatusBlockName() {
        return privateTicketStatusBlockName;
    }

    public void setPrivateTicketStatusBlockName(String privateTicketStatusBlockName) {
        this.privateTicketStatusBlockName = privateTicketStatusBlockName;
    }

    public String getPublicTicketStatusBlockName() {
        return publicTicketStatusBlockName;
    }

    public void setPublicTicketStatusBlockName(String publicTicketStatusBlockName) {
        this.publicTicketStatusBlockName = publicTicketStatusBlockName;
    }

    public String getStatusInicial() {
        return statusInicial;
    }

    public void setStatusInicial(String statusInicial) {
        this.statusInicial = statusInicial;
    }

    public PrivateFields getPrivateFields() {
        return privateFields;
    }

    public PublicFields getPublicFields() {
        return publicFields;
    }

    // ------------------------------------------------------------------
    // Bloco privado "Taiga": ID Taiga + Link Taiga
    // ------------------------------------------------------------------

    public static class PrivateFields {
        private String idTaiga = "ID Taiga";
        private String linkTaiga = "Link Taiga";

        public String getIdTaiga() {
            return idTaiga;
        }

        public void setIdTaiga(String idTaiga) {
            this.idTaiga = idTaiga;
        }

        public String getLinkTaiga() {
            return linkTaiga;
        }

        public void setLinkTaiga(String linkTaiga) {
            this.linkTaiga = linkTaiga;
        }
    }

    // ------------------------------------------------------------------
    // Bloco público "Progresso do chamado": Status do chamado + Data prevista
    // ------------------------------------------------------------------

    public static class PublicFields {
        private String statusChamado = "Status do chamado";
        private String dataPrevista = "Data prevista";

        public String getStatusChamado() {
            return statusChamado;
        }

        public void setStatusChamado(String statusChamado) {
            this.statusChamado = statusChamado;
        }

        public String getDataPrevista() {
            return dataPrevista;
        }

        public void setDataPrevista(String dataPrevista) {
            this.dataPrevista = dataPrevista;
        }
    }

    // ------------------------------------------------------------------
    // Conversão para nome da API do plugin Fields
    // ------------------------------------------------------------------

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

        // GLPI Plugin Fields descarta caracteres não-ASCII diretamente (não transliteram).
        // Ex: "Previsão" → "Previso" (ã é descartado, não convertido para a).
        // NFD + remoção de combining marks produziria "Previsao" — errado.
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