package com.chris.glpi_taiga_integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GlpiTaigaIntegrationApplication {

	public static void main(String[] args) {
		SpringApplication.run(GlpiTaigaIntegrationApplication.class, args);
	}

}