package com.chris.glpi_taiga_integration.exception;

public class IntegrationAuthenticationException extends RuntimeException {
    public IntegrationAuthenticationException(String message) {
        super(message);
    }
}