package com.chris.glpi_taiga_integration.dto;
 
import com.fasterxml.jackson.annotation.JsonProperty;
 
public record GlpiUpdateTicketRequest(GlpiTicketInput input) {
 
    public record GlpiTicketInput(
        @JsonProperty("external_id")
        String externalId
    ) {}
 
    public static GlpiUpdateTicketRequest of(Long taigaIssueId) {
        return new GlpiUpdateTicketRequest(new GlpiTicketInput(String.valueOf(taigaIssueId)));
    }
}