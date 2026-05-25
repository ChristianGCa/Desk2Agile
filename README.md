# ChamaTaiga

Middleware em `Spring Boot` para integrar `GLPI` e `Taiga` via webhooks.

## O que este projeto faz

- Recebe eventos de chamados do GLPI em `POST /api/webhook/glpi`.
- Cria issue no Taiga quando a categoria do chamado corresponder ao gatilho configurado (`category-that-send-to-taiga`) **ou** quando o técnico responsável corresponder ao gatilho de técnico (`assignee-that-send-to-taiga`).
- Salva no GLPI os dados da issue criada (ID Taiga e link), usando o `Plugin Fields` — bloco **"Taiga"** (privado).
- Atualiza o bloco público **"Progresso do chamado"** com o status inicial e, opcionalmente, a data prevista.
- Recebe eventos do Taiga em `POST /api/webhook/taiga`.
- Atualiza no GLPI o status da issue quando houver mudança no Taiga.
- Roteia a issue para o projeto correto no Taiga com base na entidade do chamado no GLPI.

## Fluxo de integração

1. Chamado é criado/atualizado no GLPI.
2. Middleware valida o token Bearer do GLPI e, em seguida, categoria/técnico do chamado.
3. Verifica se já existe vínculo com issue do Taiga.
4. Se necessário, cria issue no projeto Taiga correspondente (ou fallback `Diversos`).
5. Middleware grava no bloco **"Taiga"** (privado) o ID e o link da issue criada.
6. Middleware grava no bloco **"Progresso do chamado"** (público) o status inicial.
7. Quando o status da issue muda no Taiga, o webhook do Taiga (validado por HMAC-SHA1) atualiza o bloco **"Progresso do chamado"** no GLPI.

## Blocos do Plugin Fields

| Bloco | Visibilidade | Campos |
|---|---|---|
| **Informações do Taiga** | Equipe (privado) | ID Taiga, Link Taiga |
| **Progresso do chamado** | Todos (público) | Status do chamado, Data prevista |

Os nomes dos campos são convertidos automaticamente para o formato da API do plugin Fields (sem acentos, sem espaços, com sufixo `field`). Exemplos:

| Nome no GLPI | Nome na API |
|---|---|
| ID Taiga | `idtaigafield` |
| Link Taiga | `linktaigafield` |
| Status do chamado | `statusdochamadofield` |
| Data prevista | `dataprevistafield` |

O nome do bloco define a rota do endpoint. Exemplos:

| Nome do bloco | Rota gerada |
|---|---|
| Taiga | `/PluginFieldsTickettaiga` |
| Progresso do chamado | `/PluginFieldsTicketprogressodochamado` |

## Pré-requisitos

- Java `21`
- Maven `3.9+` (ou `./mvnw`)
- Docker e Docker Compose (para execução em container)
- Instâncias acessíveis de GLPI e Taiga
- Webhooks configurados em ambos os sistemas
- Plugin `Fields` instalado e habilitado no GLPI

## Configuração GLPI e Taiga

O passo a passo completo de configuração está no arquivo:

- `glpi_taiga_configuracao.md`

Esse arquivo descreve a criação de:
- Entidades e categorias no GLPI
- Blocos e campos do `Plugin Fields` (bloco "Taiga" e bloco "Progresso do chamado")
- API legada e tokens do GLPI
- Webhooks do GLPI e do Taiga, incluindo configuração de cabeçalhos de autenticação
- Projetos no Taiga (incluindo o projeto de fallback `Diversos`)

## Segurança dos webhooks

O middleware valida a autenticidade de cada webhook recebido antes de qualquer processamento.

### GLPI → Middleware

O GLPI envia um header `Authorization` em todas as requisições de webhook. O middleware rejeita com `401` qualquer requisição sem esse header ou com token incorreto.

**Como funciona:**

```
GLPI ──► POST /api/webhook/glpi
         Header: Authorization: Bearer <WEBHOOK_GLPI_TOKEN>
```

O valor configurado em `WEBHOOK_GLPI_TOKEN` deve ser idêntico ao valor `Bearer TOKEN` definido nos webhooks do GLPI.

### Taiga → Middleware

O Taiga assina cada requisição de webhook com HMAC-SHA1 usando a *secret key* configurada no projeto. O middleware recalcula a assinatura com o body recebido e rejeita com `401` se não bater.

**Como funciona:**

```
Taiga ──► POST /api/webhook/taiga
          Header: X-Taiga-Webhook-Signature: <hmac-sha1-hex>

Middleware calcula: HMAC-SHA1(WEBHOOK_TAIGA_SECRET, body_cru)
Compara com o header recebido.
```

**Se as variáveis estiverem em branco**, a validação é desabilitada e um aviso é registrado no log. Nunca deixe em branco em produção.

### Allowlist de IPs (opcional, camada adicional)

Além da validação de token/assinatura, é possível restringir os IPs aceitos:

```yaml
security:
  webhook:
    allowed-ips:
      - "10.80.25.10"   # IP do GLPI
      - "10.80.25.11"   # IP do Taiga
```

Use `"*"` (padrão) para desabilitar a restrição por IP.

## Variáveis de ambiente (`.env`)

Copie o arquivo de exemplo e preencha com os valores reais:

```bash
cp .env.example .env
```

Variáveis principais (consulte `.env.example` para o template completo):

```env
TAIGA_WEB_URL=http://127.0.0.1:9000/
TAIGA_URL=http://127.0.0.1:9000/api/v1
TAIGA_USERNAME=taiga
TAIGA_PASSWORD=taiga

GLPI_URL=http://localhost:8080/apirest.php
GLPI_APP_TOKEN=app_token
GLPI_USER_TOKEN=user_token

# Segurança dos webhooks
WEBHOOK_GLPI_TOKEN=TOKEN-GLPI
WEBHOOK_TAIGA_SECRET=chave-secreta
```

## Configuração principal (`config/application.yaml`)

Edite `config/application.yaml` na raiz do projeto (este arquivo sobrescreve o YAML embutido no JAR em produção). Ajuste principalmente:

- `glpi.api.category-that-send-to-taiga`: categoria que dispara criação de issue (`*` = qualquer; vazio = desligado).
- `glpi.api.assignee-that-send-to-taiga`: login do técnico que dispara criação de issue (`*` = qualquer; vazio = desligado).
- `taiga.routing.entity-mappings`: mapeamento de entidade GLPI → projeto Taiga.
- `taiga.routing.fallback-project-name`: projeto usado quando não houver mapeamento.
- `glpi.plugin-fields.private-ticket-status-block-name`: nome exato do bloco privado no GLPI (padrão: `Informações do Taiga`).
- `glpi.plugin-fields.public-ticket-status-block-name`: nome exato do bloco público no GLPI (padrão: `Progresso do chamado`).
- `glpi.plugin-fields.private-fields.*`: nomes exatos dos campos do bloco privado.
- `glpi.plugin-fields.public-fields.*`: nomes exatos dos campos do bloco público.
- `glpi.plugin-fields.status-inicial`: status gravado no bloco público ao criar a issue.
- `glpi.status-map`: lista de traduções de status do Taiga para português. Cada entrada tem `taiga` (valor exato enviado pelo Taiga) e `glpi` (texto gravado no GLPI). Se um status recebido não estiver na lista, o valor original do Taiga é gravado e um aviso é registrado no log.
- `security.webhook.glpi-token`: token Bearer esperado do GLPI (via `WEBHOOK_GLPI_TOKEN`).
- `security.webhook.taiga-secret`: secret key do Taiga para validação HMAC-SHA1 (via `WEBHOOK_TAIGA_SECRET`).
- `security.webhook.allowed-ips`: IPs autorizados a chamar os webhooks.

## Build

### Build do JAR (sem Docker)

```bash
./mvnw clean package -DskipTests
```

O JAR gerado fica em `target/glpi-taiga-integration-*.jar`.

Para incluir os testes no build:

```bash
./mvnw clean package
```

### Build da imagem Docker

```bash
docker build -t glpi-taiga-middleware:latest .
```

A imagem usa build multi-stage: compila o JAR em uma imagem JDK e o executa em uma imagem JRE menor (`eclipse-temurin:21-jre-alpine`).

## Executar

### Localmente (sem Docker)

```bash
./mvnw spring-boot:run
```

Aplicação inicia, por padrão, em `http://localhost:8081`.

### Via Docker (container isolado)

Certifique-se de ter o `.env` preenchido e a imagem construída, depois:

```bash
docker run -d \
  --name glpi-taiga-middleware \
  --restart unless-stopped \
  -p 8081:8081 \
  --env-file .env \
  -v "$(pwd)/config/application.yaml:/app/config/application.yaml:ro" \
  -v "$(pwd)/logs:/app/logs" \
  -v "$(pwd)/certs:/app/certs:ro" \
  glpi-taiga-middleware:latest
```

### Via Docker Compose (recomendado para produção)

```bash
# Subir em background
docker compose up -d

# Verificar logs em tempo real
docker compose logs -f middleware

# Parar
docker compose down
```

O `docker-compose.yml` já monta:
- `./config/application.yaml` → sobrescreve o YAML embutido no JAR
- `./logs` → persiste os logs em arquivo
- `./certs` → certificados customizados importados automaticamente no truststore da JVM

### Rebuild e restart (após mudanças de código)

```bash
docker compose down
docker build -t glpi-taiga-middleware:latest .
docker compose up -d
```

Ou em um único comando:

```bash
docker compose up -d --build
```

## Certificados SSL customizados

Coloque arquivos `.crt` ou `.pem` na pasta `./certs/`. O `docker-entrypoint.sh` os importa automaticamente no truststore da JVM antes de iniciar a aplicação.

```bash
cp meu-certificado.crt ./certs/
docker compose up -d --build
```

Se não puder montar certificados, ative a flag de skip (apenas em ambientes controlados):

```env
SSL_SKIP_VERIFY=true
```

## Testes

```bash
./mvnw test
```

## Endpoints de webhook

| Endpoint | Autenticação |
|---|---|
| `POST /api/webhook/glpi` | Header `Authorization: Bearer <token>` |
| `POST /api/webhook/taiga` | Header `X-Taiga-Webhook-Signature` (HMAC-SHA1) |