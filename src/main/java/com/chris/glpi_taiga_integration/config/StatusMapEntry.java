package com.chris.glpi_taiga_integration.config;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class StatusMapEntry {
    private String taiga;
    private String glpi;

}