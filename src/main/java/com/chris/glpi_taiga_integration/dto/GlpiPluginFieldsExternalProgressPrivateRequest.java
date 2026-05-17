package com.chris.glpi_taiga_integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GlpiPluginFieldsExternalProgressPrivateRequest(GlpiPluginFieldsInput input) {

    public record GlpiPluginFieldsInput(
            @JsonProperty("items_id") Long itemsId,
            @JsonProperty("itemtype") String itemType,
            @JsonProperty("idtaigafield") String idTaigaField,
            @JsonProperty("statustaigafield") String statusTaigaField,
            @JsonProperty("linktaigafield") String linkTaigaField
            ) {
    }

    public static final String ITEM_TYPE_TICKET = "Ticket";

    public static GlpiPluginFieldsExternalProgressPrivateRequest of(Long ticketId, Long taigaIssueId, String status, String taigaUrl) {
        return new GlpiPluginFieldsExternalProgressPrivateRequest(
                new GlpiPluginFieldsInput(ticketId, ITEM_TYPE_TICKET, String.valueOf(taigaIssueId), status, taigaUrl)
        );
    }
}