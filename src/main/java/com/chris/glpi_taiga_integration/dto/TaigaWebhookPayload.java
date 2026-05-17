package com.chris.glpi_taiga_integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaigaWebhookPayload(
    String action,
    String type,
    TaigaIssueData data
) {}