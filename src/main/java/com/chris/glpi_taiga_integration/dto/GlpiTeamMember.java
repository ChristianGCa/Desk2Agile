package com.chris.glpi_taiga_integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GlpiTeamMember(
        String role,
        String name,
        @JsonProperty("realname")
        String realName,
        @JsonProperty("firstname")
        String firstName,
        @JsonProperty("display_name")
        String displayName
) {}
