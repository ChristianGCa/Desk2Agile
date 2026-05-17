package com.chris.glpi_taiga_integration.exception;

public class GlpiPluginFieldsException extends RuntimeException {
    public GlpiPluginFieldsException(String message) {
        super(message);
    }

    public GlpiPluginFieldsException(String message, Throwable cause) {
        super(message, cause);
    }
}
