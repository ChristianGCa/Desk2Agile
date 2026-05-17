package com.chris.glpi_taiga_integration.dto;
 
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
 
@JsonIgnoreProperties(ignoreUnknown = true)
public record GlpiSessionResponse(
    @JsonProperty("session_token")
    String sessionToken
) {}
 