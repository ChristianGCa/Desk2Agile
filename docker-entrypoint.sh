#!/bin/sh
set -e

CERTS_DIR="/app/certs"
JAVA_CACERTS="$JAVA_HOME/lib/security/cacerts"
TRUSTSTORE_PASS="changeit"

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

exec java $JAVA_OPTS -jar /app/app.jar "$@"