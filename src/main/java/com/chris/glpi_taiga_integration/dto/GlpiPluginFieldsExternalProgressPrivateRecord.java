package com.chris.glpi_taiga_integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Representa um registro do bloco "Taiga" do plugin Fields.
 * Campos: ID Taiga (→ idtaigafield), Link Taiga (→ linktaigafield).
 *
 * Nota: os nomes dos campos da API são derivados dos nomes configurados no
 * application.yaml (glpi.plugin-fields.private-fields.*) pela classe
 * GlpiPluginFieldsProperties#toGlpiApiFieldName().
 * Este DTO usa os valores padrão. Se você alterou os nomes no YAML,
 * atualize as anotações @JsonProperty abaixo de acordo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GlpiPluginFieldsExternalProgressPrivateRecord(
        Long id,
        @JsonProperty("items_id") Long itemsId,
        @JsonProperty("idtaigafield") String idTaigaField,
        @JsonProperty("linktaigafield") String linkTaigaField
) {}
