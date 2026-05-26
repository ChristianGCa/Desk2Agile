# Integração GLPI + Taiga

## Visão Geral

Este documento descreve a configuração de integração entre o GLPI e o Taiga utilizando um middleware intermediário.

A integração permite:

- Criar tarefas/issues no Taiga a partir de chamados do GLPI
- Atualizar status automaticamente no GLPI quando a issue muda no Taiga
- Sincronizar informações entre as plataformas
- Organizar chamados por entidades/projetos

---

# GLPI

## 1. Criando Entidades

As entidades são utilizadas para separar projetos e direcionar chamados para projetos equivalentes no Taiga.

### Caminho

```text
Administração > Entidades > Adicionar
```

### Exemplo

| Campo | Valor |
|---|---|
| Nome | Projeto Suporte |
| Filho de | Entidade raiz |

Crie quantas entidades forem necessárias.

---

## 2. Criando Categorias

As categorias são utilizadas para classificação dos chamados e como gatilho de envio ao Taiga.

### Caminho

```text
Configurar > Listas suspensas > Assistência > Categorias ITIL > Adicionar
```

### Categoria N1

| Campo | Valor |
|---|---|
| Entidades filhas | Marcado |
| Nome | N1 |
| Código da categoria | 1 |

### Categoria N2

| Campo | Valor |
|---|---|
| Entidades filhas | Marcado |
| Nome | N2 |
| Código da categoria | 2 |

### Categoria Desenvolvimento

| Campo | Valor |
|---|---|
| Entidades filhas | Marcado |
| Nome | Desenvolvimento |
| Código da categoria | 3 |

> Esta é a categoria configurada em `glpi.api.category-that-send-to-taiga` por padrão. Chamados com essa categoria disparam a criação de issue no Taiga.

---

## 3. Instalando o Plugin Fields

O plugin Additional Fields armazena informações do Taiga dentro dos chamados.

### Passos

1. Instale a versão compatível do plugin Fields para sua versão do GLPI.

2. Acesse:

```text
Configurar > Plug-ins
```

3. Localize:

```text
Additional fields
```

4. Clique em:

```text
Ações > Instalar
```

5. Após a instalação, habilite o plugin.

---

# Campos Adicionais

## 4. Bloco Público: "Progresso do chamado"

Este bloco é visível para todos os usuários e exibe o andamento do chamado atualizado automaticamente pelo middleware.

### Caminho

```text
Configurar > Campos adicionais > Adicionar
```

### Configuração do bloco

| Campo | Valor |
|---|---|
| Entidades filhas | Marcado |
| Rótulo | Progresso do chamado |
| Tipo | Adicionar aba |
| Ativo | Sim |
| Tipo de item associado | Assistência - Chamados |

> O rótulo que for colocado aqui deve ser colocado em `glpi.plugin-fields.public-ticket-status-block-name` no `config/application.yaml`.

### Permissões

Em Perfis, defina todos os perfis como:

```text
Ler
```

Isso impede edição manual do conteúdo.

---

## 5. Campos do bloco "Progresso do chamado"

### Campo: Status do chamado

| Campo | Valor |
|---|---|
| Rótulo | Status do chamado |
| Tipo | Texto (Múltiplas linhas) |
| Somente leitura | Sim |
| Campo obrigatório | Não |

> O middleware grava o status da issue do Taiga neste campo. Nome na API nesse exemplo: `statusdochamadofield`.

### Campo: Data prevista

| Campo | Valor |
|---|---|
| Rótulo | Data prevista |
| Tipo | Data |
| Somente leitura | Sim |
| Campo obrigatório | Não |

> Campo reservado para a data prevista de conclusão. Nome na API: `dataprevistafield`.

---

## 6. Bloco Privado: "Informações do Taiga"

Este bloco armazena os dados internos da integração com o Taiga e é visível apenas para a equipe.

### Configuração do bloco

| Campo | Valor |
|---|---|
| Entidades filhas | Marcado |
| Rótulo | Informações do Taiga |
| Tipo | Adicionar aba |
| Ativo | Sim |
| Tipo de item associado | Assistência - Chamados |

> O rótulo deve corresponder ao valor de `glpi.plugin-fields.private-ticket-status-block-name` no `config/application.yaml`.

### Permissões

| Perfil | Permissão |
|---|---|
| Self-Service | Sem acesso |
| Observer | Sem acesso |
| Demais perfis | Ler |

---

## 7. Campos do bloco "Informações do Taiga"

### Campo: ID da Issue

| Campo | Valor |
|---|---|
| Rótulo | ID da Issue |
| Tipo | Número |
| Somente leitura | Sim |
| Campo obrigatório | Não |

> Identificador numérico da issue no Taiga.

### Campo: Link da Issue

| Campo | Valor |
|---|---|
| Rótulo | Link da Issue |
| Tipo | URL |
| Somente leitura | Sim |
| Campo obrigatório | Não |

> URL direta para a issue no Taiga.
> Caso o tipo URL apresente erro no GLPI, utilize o tipo Texto.

---

## Resumo dos blocos e campos

| Bloco | Visibilidade | Campo | Tipo | Nome na API |
|---|---|---|---|---|
| Progresso do chamado | Todos | Status do chamado | Texto (múltiplas linhas) | `statusdochamadofield` |
| Progresso do chamado | Todos | Data prevista | Data | `dataprevistafield` |
| Informações do Taiga | Equipe | ID da Issue | Número | `iddaissuefield` |
| Informações do Taiga | Equipe | Link da Issue | URL | `linkdaissuefield` |

> Os nomes na API são gerados automaticamente pelo middleware a partir dos rótulos configurados no `config/application.yaml`: remove acentos, espaços e caracteres especiais, converte para minúsculas e adiciona o sufixo `field`. Se você usar rótulos diferentes dos padrões acima, atualize `glpi.plugin-fields.*` no `config/application.yaml` de acordo.

---

# API do GLPI

## 8. Habilitando API Legada

### Caminho

```text
Configurar > Geral > API
```

### Configuração

| Campo | Valor |
|---|---|
| Habilitar API legada | Sim |

---

## 9. Configurando Cliente de API

### Caminho

```text
Clientes de API (API Legado)
```

Selecione um cliente existente ou crie um novo.

### Exemplo de Configuração

| Campo | Valor |
|---|---|
| Intervalo IPv4 | 172.20.0.10 |
| app_token | EXEMPLO_APP_TOKEN_SEGURO |

> Utilize apenas IPs internos confiáveis.

---

## 10. Criando User Token

### Caminho

```text
Usuário > Minhas configurações > Senhas e chaves de acesso
```

### Configuração

| Campo | Valor |
|---|---|
| Token de API | EXEMPLO_USER_TOKEN |

---

# Webhooks do GLPI

O GLPI suporta o envio de **cabeçalhos HTTP personalizados** em cada requisição de webhook. O middleware usa esse recurso para validar a autenticidade das requisições recebidas: o GLPI envia um header `Authorization: Bearer <token>` e o middleware rejeita com `401` qualquer requisição que não o apresente ou que traga um token diferente do configurado.

## Como adicionar cabeçalhos personalizados no GLPI

Ao criar ou editar um webhook, role a página até a seção **"Cabeçalhos HTTP personalizados"**. Essa seção fica abaixo dos campos principais do formulário.

### Passos

1. Clique em **"Adicionar cabeçalho"**.
2. No campo **Nome do cabeçalho**, digite:
   ```
   Authorization
   ```
3. No campo **Valor**, digite:
   ```
   Bearer TOKEN-GLPI
   ```
   Substitua `TOKEN-GLPI` pelo valor definido em `WEBHOOK_GLPI_TOKEN` no `.env` do middleware.
4. Salve o webhook.

> **Atenção:** o valor do campo deve incluir a palavra `Bearer` seguida de um espaço e depois o token. Exemplo completo: `Bearer meu-token-secreto-aqui`. O middleware espera exatamente esse formato.

### Exemplo visual

```
┌─────────────────────────────────────────────────────┐
│  Cabeçalhos HTTP personalizados                     │
├──────────────────────────┬──────────────────────────┤
│  Nome do cabeçalho       │  Valor                   │
├──────────────────────────┼──────────────────────────┤
│  Authorization           │  Bearer TOKEN-GLPI        │
└──────────────────────────┴──────────────────────────┘
```

> O token configurado aqui deve ser **idêntico** ao definido em `WEBHOOK_GLPI_TOKEN` no `.env`. Se os valores não baterem, o middleware retorna `401 Não autorizado` e o chamado não é integrado.

---

## 11. Webhook de Criação

### Caminho

```text
Configurar > Webhooks > Adicionar
```

### Configuração

| Campo | Valor |
|---|---|
| Entidades filhas | Marcado |
| Nome | Novo |
| Tipo de item | Chamado |
| Evento | Novo |
| URL | https://middleware.exemplo.local/api/webhook/glpi |
| Método HTTP | POST |
| Salvar corpo da resposta | Sim |
| Log no histórico | Sim |

### Cabeçalho de autenticação

Na seção **"Cabeçalhos HTTP personalizados"**, adicione:

| Nome do cabeçalho | Valor |
|---|---|
| `Authorization` | `Bearer TOKEN-GLPI` |

> Substitua `TOKEN-GLPI` pelo valor real definido em `WEBHOOK_GLPI_TOKEN` no `.env`.

---

## 12. Webhook de Atualização

### Configuração

| Campo | Valor |
|---|---|
| Entidades filhas | Marcado |
| Nome | Atualizar |
| Tipo de item | Chamado |
| Evento | Atualizar |
| URL | https://middleware.exemplo.local/api/webhook/glpi |
| Método HTTP | POST |
| Salvar corpo da resposta | Sim |
| Log no histórico | Sim |

### Cabeçalho de autenticação

Adicione o **mesmo cabeçalho** configurado no webhook de criação:

| Nome do cabeçalho | Valor |
|---|---|
| `Authorization` | `Bearer TOKEN-GLPI` |

> Use o mesmo token em todos os webhooks do GLPI. O middleware valida apenas se o token recebido bate com o configurado — não diferencia por evento.

---

# Taiga

## 13. Criando Projetos

Após realizar login no Taiga:

### Caminho

```text
Create Project > Kanban
```

### Exemplo

| Campo | Valor |
|---|---|
| Nome | Projeto Suporte |
| Descrição | Projeto de integração com GLPI |

Crie um projeto para cada entidade criada no GLPI.

---

## 14. Habilitando Issues

Dentro de cada projeto:

```text
Settings > Project > Modules
```

Ative:

```text
Issues
```

---

## 15. Webhook do Taiga

### Caminho

```text
Settings > Integrations > Webhooks
```

### Configuração

| Campo | Valor |
|---|---|
| Name | Atualizar GLPI |
| URL | https://middleware.exemplo.local/api/webhook/taiga |
| Secret key | `chave-secreta` |

> **A secret key é obrigatória.** O Taiga usa esse valor para assinar cada requisição com HMAC-SHA1 e envia a assinatura no header `X-Taiga-Webhook-Signature`. O middleware recalcula a assinatura com o body recebido e rejeita com `401` se não bater.
>
> Use um valor longo e aleatório (mínimo 32 caracteres recomendado). O mesmo valor deve ser configurado em `WEBHOOK_TAIGA_SECRET` no `.env` do middleware.
>
> Configure o webhook em **cada projeto** que deve sincronizar status com o GLPI.

### Como o Taiga gera a assinatura

```
X-Taiga-Webhook-Signature = HMAC-SHA1(secret_key, body_cru_utf8)
```

O resultado é enviado em hexadecimal. O middleware replica esse cálculo para verificar a autenticidade.

---

## 16. Projeto Padrão (Fallback)

Crie um projeto adicional chamado:

```text
Diversos
```

Este projeto recebe chamados sem entidade atribuída no GLPI. O nome é configurável em `taiga.routing.fallback-project-name`.

---

# Mapeamento GLPI → Taiga

Após criar entidades no GLPI e projetos no Taiga, configure o mapeamento no `config/application.yaml`:

```yaml
taiga:
   routing:
      fallback-project-name: Diversos
      entity-mappings:
         - glpi-entity-name: Projeto Suporte
           taiga-project-name: Projeto Suporte
         - glpi-entity-name: Serviço 1
           taiga-project-name: Serviço 1
```

O middleware busca a entidade do chamado no GLPI, localiza o mapeamento correspondente e cria a issue no projeto Taiga configurado. Se não encontrar mapeamento, usa o projeto `fallback-project-name`.

---

# Mapeamento de status do Taiga

Por padrão, o middleware já traduz os 10 status padrão do Taiga para português:

| Status no Taiga | Gravado no GLPI |
|---|---|
| New | Novo |
| In progress | Em andamento |
| Ready for test | Pronto para teste |
| Done | Concluído |
| Archived | Arquivado |
| Closed | Fechado |
| Needs info | Aguardando informação |
| Rejected | Rejeitado |
| Postponed | Adiado |
| Ready | Preparado |

Nenhuma configuração é necessária para esses status.

Para **sobrescrever** uma tradução ou **adicionar um status customizado** do Taiga, declare entradas em `glpi.status-map` no `config/application.yaml`:

```yaml
glpi:
   # Só declare o que diferir dos defaults acima
   status-map:
      - taiga: In progress
        glpi: Em desenvolvimento   # sobrescreve o default "Em andamento"
      - taiga: MeuStatusCustom
        glpi: Meu texto no GLPI
```

- O campo `taiga` deve ser o nome **exato** do status como aparece no Taiga (case-insensitive).
- O campo `glpi` é o texto que será gravado no campo **Status do chamado** do GLPI.
- Se um status recebido do Taiga não tiver mapeamento, o valor original é gravado sem alteração e um aviso é registrado no log.

> **Dica:** para descobrir o nome exato de um status customizado, verifique em `Settings > Issues > Issue statuses` no projeto do Taiga ou observe o log do middleware ao receber um webhook com o status desejado.

---

# Variáveis de ambiente

Referência completa das variáveis do arquivo `.env`:

| Variável | Obrigatória | Descrição |
|---|---|---|
| `TAIGA_URL` | Sim | URL base da API do Taiga (ex: `http://taiga.local/api/v1`) |
| `TAIGA_WEB_URL` | Sim | URL base web do Taiga (usada para montar links de issues) |
| `TAIGA_USERNAME` | Sim | Usuário do Taiga usado pelo middleware |
| `TAIGA_PASSWORD` | Sim | Senha do usuário Taiga |
| `GLPI_URL` | Sim | URL da API REST do GLPI (ex: `http://glpi.local/apirest.php`) |
| `GLPI_APP_TOKEN` | Sim | App token do cliente de API do GLPI |
| `GLPI_USER_TOKEN` | Sim | User token do usuário GLPI |
| `WEBHOOK_GLPI_TOKEN` | Recomendado | Token Bearer enviado pelo GLPI no header `Authorization` |
| `WEBHOOK_TAIGA_SECRET` | Recomendado | Secret key configurada nos webhooks do Taiga (HMAC-SHA1) |
| `SSL_SKIP_VERIFY` | Não | `true` para aceitar certificados autoassinados (padrão: `false`) |