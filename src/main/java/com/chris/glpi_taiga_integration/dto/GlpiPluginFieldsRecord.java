package com.chris.glpi_taiga_integration.dto;

public record GlpiPluginFieldsRecord(
        Long id,
        Long itemsId,
        String taigaIdValue,
        String taigaLinkValue
) {
}