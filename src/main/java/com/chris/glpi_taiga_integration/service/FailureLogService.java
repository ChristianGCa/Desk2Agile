package com.chris.glpi_taiga_integration.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Serviço simples para registrar falhas de integração em um arquivo de texto.
 */
@Service
public class FailureLogService {

    private static final Logger log = LoggerFactory.getLogger(FailureLogService.class);

    @Value("${logging.integration.failure-file:integration_failures.log}")
    private String logFile;

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Registra uma falha no arquivo de log.
     *
     * @param context Descrição do contexto onde a falha ocorreu (ex: "Webhook GLPI", "Criação de Issue")
     * @param e A exceção capturada
     */
    public void logFailure(String context, Exception e) {
        String timestamp = LocalDateTime.now().format(formatter);
        String exceptionName = e.getClass().getSimpleName();
        String exceptionMessage = e.getMessage();

        String entry = String.format("[%s] CONTEXTO: %s | ERRO: %s | MENSAGEM: %s%n",
                timestamp, context, exceptionName, exceptionMessage);

        try {
            Path path = Paths.get(logFile);

            // Cria o arquivo se não existir (e diretórios pai se necessário)
            if (!Files.exists(path)) {
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.createFile(path);
                log.info("Arquivo de log de falhas criado: {}", logFile);
            }

            // Escreve a entrada no final do arquivo
            Files.writeString(path, entry, StandardOpenOption.APPEND);

        } catch (IOException ioException) {
            log.error("Falha crítica ao tentar escrever no arquivo de log de falhas ({}): {}", logFile, ioException.getMessage());
        }
    }

    /**
     * Registra uma falha com uma mensagem customizada, sem necessariamente uma exceção.
     *
     * @param context Descrição do contexto
     * @param message Mensagem de erro
     */
    public void logFailure(String context, String message) {
        String timestamp = LocalDateTime.now().format(formatter);
        String entry = String.format("[%s] CONTEXTO: %s | MENSAGEM: %s%n",
                timestamp, context, message);

        try {
            Path path = Paths.get(logFile);
            if (!Files.exists(path)) {
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.createFile(path);
            }
            Files.writeString(path, entry, StandardOpenOption.APPEND);
        } catch (IOException ioException) {
            log.error("Falha crítica ao tentar escrever no arquivo de log de falhas ({}): {}", logFile, ioException.getMessage());
        }
    }
}
