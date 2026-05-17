package com.chris.glpi_taiga_integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * O ID do ticket (field "2") é extraído via {@link #extractTicketId()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GlpiSearchResponse(
    Integer totalcount,
    Integer count,
    List<Map<String, Object>> data
) {

    public Long extractTicketId() { 
        if (data == null || data.isEmpty()) return null;
        Object value = data.get(0).get("2");
        if (value == null) return null;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Long l) return l;
        if (value instanceof String s) return Long.parseLong(s);
        return null;
    }
}