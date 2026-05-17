package com.chris.glpi_taiga_integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GlpiItem(
        Long id,
        String name,
        String content,
        GlpiCategory category,
        @JsonProperty("external_id")
        String externalId,
        List<GlpiTeamMember> team) {

    public GlpiItem {
        if (team == null) {
            team = List.of();
        }
    }
}