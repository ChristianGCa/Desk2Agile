package com.chris.glpi_taiga_integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaigaUserStoryDetailsResponse(
        Long id,
        Long ref,
        @JsonProperty("due_date") String dueDate,
        @JsonProperty("project") Long projectId,
        @JsonProperty("status") Long statusId,
        @JsonProperty("generated_from_issue") Long generatedFromIssue
) {}
