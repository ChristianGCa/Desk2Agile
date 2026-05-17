package com.chris.glpi_taiga_integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaigaIssueRequest(
    Long project,
    String subject,
    String description,
    Integer priority,
    Integer severity,
    Integer type
) {}