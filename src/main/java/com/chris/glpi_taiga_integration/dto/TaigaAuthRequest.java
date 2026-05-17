package com.chris.glpi_taiga_integration.dto;

public record TaigaAuthRequest(
    String type,
    String username,
    String password
) {}