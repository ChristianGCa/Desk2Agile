package com.chris.glpi_taiga_integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaigaIssueData(
    Long id,
    Long ref,
    String subject,
    String description,
    TaigaStatusData status
) {}