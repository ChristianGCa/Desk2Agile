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
2. Middleware valida categoria/técnico e verifica se já existe vínculo com issue do Taiga.
3. Se necessário, cria issue no projeto Taiga correspondente (ou fallback `Diversos`).
4. Middleware grava no bloco **"Taiga"** (privado) o ID e o link da issue criada.
5. Middleware grava no bloco **"Progresso do chamado"** (público) o status inicial.
6. Quando o status da issue muda no Taiga, o webhook do Taiga atualiza o bloco **"Progresso do chamado"** no GLPI.

## Blocos do Plugin Fields

| Bloco | Visibilidade | Campos |
|---|---|---|
| **Taiga** | Equipe (privado) | ID Taiga, Link Taiga |
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
- Webhooks do GLPI e do Taiga
- Projetos no Taiga (incluindo o projeto de fallback `Diversos`)

## Variáveis de ambiente (`.env`)

Use um arquivo `.env` na raiz com:

```env
TAIGA_WEB_URL=http://127.0.0.1:9000/
TAIGA_URL=http://127.0.0.1:9000/api/v1
TAIGA_USERNAME=taiga
TAIGA_PASSWORD=taiga

GLPI_URL=http://localhost:8080/apirest.php
GLPI_APP_TOKEN=app_token
GLPI_USER_TOKEN=user_token

WEBHOOK_ALLOWED_IP_GLPI=172.18.0.6
WEBHOOK_ALLOWED_IP_TAIGA=172.19.0.8

# Log em arquivo — deixe vazio ou remova para desativar
LOG_FILE=/app/logs/app.log
```

## Configuração principal (`config/application.yaml`)

Ajuste principalmente:

- `glpi.api.category-that-send-to-taiga`: categoria que dispara criação de issue (`*` = qualquer; vazio = desligado).
- `glpi.api.assignee-that-send-to-taiga`: login do técnico que dispara criação de issue (`*` = qualquer; vazio = desligado).
- `taiga.routing.entity-mappings`: mapeamento de entidade GLPI → projeto Taiga.
- `taiga.routing.fallback-project-name`: projeto usado quando não houver mapeamento.
- `glpi.plugin-fields.private-ticket-status-block-name`: nome exato do bloco privado no GLPI (padrão: `Taiga`).
- `glpi.plugin-fields.public-ticket-status-block-name`: nome exato do bloco público no GLPI (padrão: `Progresso do chamado`).
- `glpi.plugin-fields.private-fields.*`: nomes exatos dos campos do bloco privado.
- `glpi.plugin-fields.public-fields.*`: nomes exatos dos campos do bloco público.
- `glpi.plugin-fields.status-inicial`: status gravado no bloco público ao criar a issue.
- `security.webhook.allowed-ips`: IPs autorizados a chamar os webhooks.

## Logs

O projeto gera dois arquivos de log independentes, ambos na pasta `./logs/` (mapeada via volume no Docker):

| Arquivo | Conteúdo |
|---|---|
| `app.log` | Eventos gerais de operação (INFO) |
| `integration_failures.log` | Somente erros de integração |

**Para ativar o log geral em arquivo**, defina `LOG_FILE` no `.env`:

```env
LOG_FILE=/app/logs/app.log
```

**Para desativar**, deixe vazio ou remova a linha:

```env
LOG_FILE=
```

O arquivo `integration_failures.log` é sempre gerado, independente do `LOG_FILE`.

Para diagnóstico, o nível de log pode ser elevado para `DEBUG` em `config/application.yaml`:

```yaml
logging:
  level:
    com:
      chris:
        glpi_taiga_integration: DEBUG
```

## Executar localmente

```bash
./mvnw spring-boot:run
```

Aplicação inicia, por padrão, em `http://localhost:8081`.

## Endpoints de webhook

- `POST /api/webhook/glpi`
- `POST /api/webhook/taiga`