package com.chris.glpi_taiga_integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Representa um registro do bloco "Progresso do chamado" do plugin Fields.
 * Campos: Status do chamado (→ statusdochamadofield), Data prevista (→ dataprevistafield).
 *
 * Nota: os nomes dos campos da API são derivados dos nomes configurados no
 * application.yaml (glpi.plugin-fields.public-fields.*) pela classe
 * GlpiPluginFieldsProperties#toGlpiApiFieldName().
 * Este DTO usa os valores padrão. Se você alterou os nomes no YAML,
 * atualize as anotações @JsonProperty abaixo de acordo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GlpiPluginFieldsExternalProgressPublicRecord(
        Long id,
        @JsonProperty("items_id") Long itemsId,
        @JsonProperty("statusdochamadofield") String statusDoChamadoField,
        @JsonProperty("dataprevistafield") String dataPrevistaField
) {}
