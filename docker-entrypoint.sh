#!/bin/sh
set -e

CERTS_DIR="/app/certs"
JAVA_CACERTS="$JAVA_HOME/lib/security/cacerts"
TRUSTSTORE_PASS="changeit"

# Aplica as variáveis PUID e PGID (padrão 1000) para alinhar com o usuário do host
PUID=${PUID:-1000}
PGID=${PGID:-1000}

# Altera dinamicamente o ID do appuser/appgroup para bater com o do host
groupmod -o -g "$PGID" appgroup >/dev/null 2>&1 || true
usermod -o -u "$PUID" appuser >/dev/null 2>&1 || true

# Garante que a pasta de logs pertença a esse usuário recém-ajustado (sem abrir 777)
if [ -d "/app/logs" ]; then
    chown -R appuser:appgroup /app/logs || true
fi

# Importa certificados customizados se a pasta /app/certs existir e tiver arquivos .crt
if [ -d "$CERTS_DIR" ]; then
    for cert in "$CERTS_DIR"/*.crt "$CERTS_DIR"/*.pem; do
        [ -f "$cert" ] || continue
        alias=$(basename "$cert" | sed 's/\.[^.]*$//')
        echo "[entrypoint] Importando certificado: $cert (alias: $alias)"
        keytool -importcert \
            -noprompt \
            -trustcacerts \
            -alias "$alias" \
            -file "$cert" \
            -keystore "$JAVA_CACERTS" \
            -storepass "$TRUSTSTORE_PASS" 2>/dev/null || \
            echo "[entrypoint] AVISO: falha ao importar $cert (já existe ou inválido)"
    done
fi

exec su-exec appuser java $JAVA_OPTS -jar /app/app.jar "$@"
