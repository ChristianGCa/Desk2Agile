#!/bin/sh
set -e

CERTS_DIR="/app/certs"
TRUSTSTORE="/app/truststore.jks"
TRUSTSTORE_PASS="changeit"

# Importa certificados customizados para o truststore local (não requer root)
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
            -keystore "$TRUSTSTORE" \
            -storepass "$TRUSTSTORE_PASS" 2>/dev/null || \
            echo "[entrypoint] AVISO: falha ao importar $cert (já existe ou inválido)"
    done
fi

exec java \
    -Djavax.net.ssl.trustStore="$TRUSTSTORE" \
    -Djavax.net.ssl.trustStorePassword="$TRUSTSTORE_PASS" \
    $JAVA_OPTS \
    -jar /app/app.jar "$@"
