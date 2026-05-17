package com.chris.glpi_taiga_integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaigaIssueDetailsResponse(
        Long id,
        Long ref,
        @JsonProperty("due_date") String dueDate,
        @JsonProperty("project") Long projectId) {
}
