package com.chris.glpi_taiga_integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GlpiPluginFieldsExternalProgressPublicRequest(GlpiPluginFieldsExternalProgressInput input) {

    public record GlpiPluginFieldsExternalProgressInput(
            @JsonProperty("items_id") Long itemsId,
            @JsonProperty("itemtype") String itemType,
            @JsonProperty("statusfield") String statusProgressoExternoField
    ) {}

    public static final String ITEM_TYPE_TICKET = "Ticket";

    public static GlpiPluginFieldsExternalProgressPublicRequest of(Long ticketId, String status) {
        return new GlpiPluginFieldsExternalProgressPublicRequest(
                new GlpiPluginFieldsExternalProgressInput(ticketId, ITEM_TYPE_TICKET, status)
        );
    }
}
