# kms-integration

Serviço de assinatura blockchain para ambientes AWS regulados e isolados.

Substitui a integração com DFNS (SaaS externo) pelo AWS KMS para assinar transações Ethereum/EVM. Toda comunicação é feita via **AWS SQS** — sem portas de entrada expostas, sem dependência de internet.

---

## Arquitetura

```
BFF (Node.js)
  │  publica mensagem na fila SQS com campo "type" e "responseQueue"
  ▼
Fila SQS FIFO (entrada — única fila para todos os tipos)
  │  @SqsListener → MessageDispatcher roteia pelo campo "type"
  ▼
kms-integration (Java 21 / Spring Boot 3)
  │  assina via AWS KMS (ECC_SECG_P256K1)
  │  submete transação via RPC (Besu / EVM)
  │  grava log no PostgreSQL
  ▼
Rede DLT permissionada (Besu)
  │  resultado publicado
  ▼
responseQueue (fila SQS informada na mensagem)
  │  ou tópico SNS FIFO padrão se responseQueue não informado
  ▼
BFF (Node.js)
```

Todos os serviços AWS (SQS, SNS, KMS, Secrets Manager) são acessados via **VPC Interface Endpoints** — nenhum tráfego sai da VPC do cliente.

---

## Wallet da plataforma (admin)

Na **primeira inicialização**, o serviço verifica se existe uma wallet com papel `ADMIN` no banco de dados:

- Se `KMS_KEY_ID` estiver configurado e a wallet ainda não estiver no banco: registra a chave como wallet admin.
- Se `KMS_KEY_ID` **não** estiver configurado e não houver wallet admin no banco: **cria automaticamente uma nova chave KMS** (ECC_SECG_P256K1 / SIGN_VERIFY), deriva o endereço Ethereum, persiste no banco com `role=ADMIN` e define o `KMS_KEY_ID` em runtime.
- Nas inicializações seguintes: carrega a wallet admin existente do banco — nenhuma chave nova é criada.

Essa wallet é usada para todas as operações da plataforma (deploy de contratos, mint, burn, pause, unpause, transferências da plataforma). Cada usuário tem sua própria chave KMS, criada via `ACCOUNT_CREATE`.

> **`KMS_KEY_ID` é opcional.** Se não configurado, a chave é auto-provisionada no primeiro startup.

---

## Tipos de mensagem

Todas as mensagens chegam na mesma fila SQS. O campo `type` determina o roteamento.

| `type` | Descrição | Consumer |
|---|---|---|
| `TOKEN_CREATION` | Deploy de contrato ERC-20, ERC-721 ou ERC-1155 | `CreationConsumer` |
| `TOKEN_EVENT` | Mint, burn, transfer, pause ou unpause | `TokenEventConsumer` |
| `TOKEN_TRANSFER` | Transferência de tokens pela wallet da plataforma | `TransferConsumer` |
| `USER_TRANSFER` | Transferência de tokens pela wallet KMS do usuário | `UserTransferConsumer` |
| `BALANCE_QUERY` | Consulta de saldo ERC-20 | `BalanceConsumer` |
| `ACCOUNT_CREATE` | Criação de wallet KMS para novo cliente | `AccountConsumer` |

---

## Formato das mensagens

### Campos obrigatórios em todas as mensagens

```json
{
  "type": "TOKEN_CREATION",
  "responseQueue": "https://sqs.us-east-1.amazonaws.com/123456789012/minha-fila-resposta.fifo"
}
```

- **`type`** — tipo da operação (ver tabela acima)
- **`responseQueue`** — URL da fila SQS onde a resposta deve ser entregue. Se omitido, a resposta vai para o tópico SNS configurado em `SNS_TOPIC_ARN`

### Envelope SNS (quando a fila é alimentada via SNS)

Se o BFF publica em um tópico SNS que entrega na fila SQS, o `MessageDispatcher` lê o `type` do `MessageAttribute` do envelope SNS automaticamente. Em testes diretos na fila, o campo `type` pode estar na raiz do JSON.

---

## Exemplos de mensagens

A pasta `examples/` contém um JSON de exemplo para cada operação:

| Arquivo | Operação |
|---|---|
| `01_deploy_erc20.json` | Deploy de contrato ERC-20 |
| `02_deploy_erc721.json` | Deploy de contrato ERC-721 |
| `03_deploy_erc1155.json` | Deploy de contrato ERC-1155 |
| `04_mint.json` | Mint de tokens |
| `05_burn.json` | Burn de tokens |
| `06_transfer_plataforma.json` | Transferência pela wallet da plataforma |
| `07_transfer_usuario.json` | Transferência pela wallet KMS do usuário |
| `08_pause.json` | Pausar contrato |
| `09_unpause.json` | Despausar contrato |
| `10_balance.json` | Consulta de saldo |
| `11_account_create.json` | Criar wallet KMS para cliente |

Para enviar um exemplo para a fila (LocalStack ou AWS):

```bash
./examples/send.sh 04_mint.json
```

### Exemplo — criação de conta (`ACCOUNT_CREATE`)

```json
{
  "type": "ACCOUNT_CREATE",
  "idempotencyKey": "account-create-001",
  "responseQueue": "https://sqs.us-east-1.amazonaws.com/123456789012/minha-fila-resposta.fifo",
  "event": "account.create.requested",
  "clientId": "cliente-abc123"
}
```

- **`clientId`** — identificador único do cliente no sistema do chamador. Definido pelo BFF; o `keyId` interno do KMS nunca é exposto.
- **`network` não é necessário** — o endereço EVM é derivado da chave pública e é válido em todas as redes EVM (Besu, Sepolia, mainnet etc.).
- **Idempotência por `clientId`**: se o mesmo `clientId` for enviado mais de uma vez, o serviço retorna o endereço já existente sem criar nova chave KMS, mesmo que o `idempotencyKey` seja diferente.

---

## Formato das respostas

### Sucesso — criação de token (`TOKEN_CREATION`)

```json
{
  "event": "token.creation.succeeded",
  "idempotencyKey": "deploy-erc20-001",
  "timestamp": "2026-06-29T00:00:00Z",
  "network": { "name": "besu-local", "chainId": 1337 },
  "token": {
    "standard": "ERC20",
    "name": "Real Brasileiro",
    "symbol": "BRLN",
    "contractAddress": "0xContrato..."
  },
  "deployment": {
    "contractAddress": "0xContrato...",
    "transactionHash": "0xTxHash...",
    "blockNumber": 42,
    "gasUsed": "210000"
  },
  "metadata": { "correlationId": "corr-001", "processedBy": "kms-integration", "durationMs": 3200 }
}
```

### Sucesso — evento de token (`TOKEN_EVENT`: mint, burn, etc.)

```json
{
  "event": "token.event.succeeded",
  "idempotencyKey": "mint-001",
  "result": {
    "txHash": "0xTxHash...",
    "blockNumber": 43,
    "gasUsed": "80000"
  },
  "metadata": { "correlationId": "corr-001", "processedBy": "kms-integration", "durationMs": 1800 }
}
```

### Sucesso — criação de wallet (`ACCOUNT_CREATE`)

```json
{
  "event": "account.create.succeeded",
  "clientId": "cliente-abc123",
  "idempotencyKey": "account-create-001",
  "wallet": {
    "address": "0xEnderecoEthereum..."
  },
  "timestamp": "2026-06-29T00:00:00Z",
  "metadata": { "processedBy": "kms-integration", "durationMs": 950 }
}
```

> O `keyId` interno do KMS **não é retornado** na resposta. O BFF deve usar o `address` para identificar a wallet do cliente em operações futuras.

### Erro (qualquer operação)

```json
{
  "event": "token.creation.failed",
  "idempotencyKey": "deploy-erc20-001",
  "error": {
    "code": "DEPLOYMENT_FAILED",
    "message": "descrição do erro"
  },
  "metadata": { "correlationId": "corr-001", "processedBy": "kms-integration", "durationMs": 120 }
}
```

---

## Banco de dados PostgreSQL

O serviço persiste dados em duas tabelas, gerenciadas pelo **Liquibase** (migrations executadas automaticamente no startup com o usuário `dpl_devops_liquibase`).

### `request_log` — log de todas as requisições

| Coluna | Tipo | Descrição |
|---|---|---|
| `id` | UUID | Identificador único |
| `idempotency_key` | VARCHAR(255) | Chave de idempotência da requisição |
| `type` | VARCHAR(50) | Tipo da operação (ex: `TOKEN_CREATION`) |
| `status` | VARCHAR(20) | `RECEIVED`, `COMPLETED` ou `FAILED` |
| `response_queue` | VARCHAR(512) | Fila de resposta informada na mensagem |
| `payload` | TEXT | Payload recebido (JSON) |
| `response` | TEXT | Payload de resposta publicado (JSON) |
| `error_message` | TEXT | Mensagem de erro em caso de falha |
| `created_at` | TIMESTAMP | Data/hora de recebimento |
| `updated_at` | TIMESTAMP | Data/hora da última atualização |

### `wallet` — wallets KMS

| Coluna | Tipo | Descrição |
|---|---|---|
| `id` | UUID | Identificador único |
| `user_id` | VARCHAR(255) | `clientId` do cliente (ou `"platform"` para a wallet admin) |
| `key_id` | VARCHAR(512) | ARN da chave KMS — uso interno, nunca exposto ao chamador |
| `address` | VARCHAR(42) | Endereço Ethereum derivado (único, válido em todas as redes EVM) |
| `network` | VARCHAR(100) | `"evm"` para wallets de cliente; `"platform"` para wallet admin |
| `alias` | VARCHAR(255) | Alias KMS (`alias/tokeniza-client-{clientId}` ou `alias/tokeniza-platform-admin`) |
| `role` | VARCHAR(20) | `"ADMIN"` (wallet da plataforma) ou `"USER"` (wallet de cliente) |
| `created_at` | TIMESTAMP | Data/hora de criação |

---

## Contratos Solidity

Os bytecodes dos contratos ERC-20, ERC-721 e ERC-1155 são **compilados no build** a partir dos fontes em `src/main/solidity/` usando o `web3j-maven-plugin`. As classes Java geradas (com a constante `BINARY`) ficam em `target/generated-sources/web3j/`.

Não há variáveis de ambiente para bytecode — basta editar os arquivos `.sol` e recompilar com `mvn package`.

| Arquivo | Contrato |
|---|---|
| `src/main/solidity/TokenizaERC20.sol` | ERC-20 com mint, burn, pause/unpause, transferOwnership |
| `src/main/solidity/TokenizaERC721.sol` | ERC-721 com mint, burn, pause/unpause, setBaseUri |
| `src/main/solidity/TokenizaERC1155.sol` | ERC-1155 com mint, mintBatch, burn, pause/unpause, setUri |

---

## Configuração da chave KMS da plataforma

A wallet admin pode ser **auto-provisionada** (recomendado) ou pré-configurada via variável de ambiente.

### Opção 1 — Auto-provisionamento (sem `KMS_KEY_ID`)

Não configure `KMS_KEY_ID`. Na primeira inicialização, o serviço cria automaticamente uma chave KMS ECC_SECG_P256K1, registra no banco com `role=ADMIN` e a usa como assinador da plataforma. Nas inicializações seguintes, a chave é carregada do banco.

### Opção 2 — Chave pré-existente

```bash
# Criar a chave de plataforma
KEY_ID=$(aws kms create-key \
  --key-spec ECC_SECG_P256K1 \
  --key-usage SIGN_VERIFY \
  --description "Tokeniza — chave de assinatura da plataforma" \
  --query KeyMetadata.KeyId --output text)

# Adicionar alias
aws kms create-alias \
  --alias-name alias/tokeniza-platform-admin \
  --target-key-id "$KEY_ID"

echo "KMS_KEY_ID=$KEY_ID"
```

Configurar `KMS_KEY_ID=$KEY_ID` no `.env`. Na primeira inicialização, a chave é registrada automaticamente no banco com `role=ADMIN`.

---

## Fila SQS — criação

```bash
aws sqs create-queue \
  --queue-name atoken-integracao-kms-sqs-dev.fifo \
  --attributes '{
    "FifoQueue": "true",
    "ContentBasedDeduplication": "true",
    "VisibilityTimeout": "300",
    "MessageRetentionPeriod": "86400",
    "ReceiveMessageWaitTimeSeconds": "20"
  }' \
  --region us-east-1
```

Atributos recomendados:
- `VisibilityTimeout`: 300 s — tempo para mineração da transação + polling do recibo
- `MessageRetentionPeriod`: 86400 s (1 dia)
- `ReceiveMessageWaitTimeSeconds`: 20 s (long polling)

---

## Permissões IAM

A instância EC2 (ou task ECS) precisa da seguinte política IAM:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AcessoSQS",
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes",
        "sqs:SendMessage",
        "sqs:GetQueueUrl",
        "sqs:CreateQueue"
      ],
      "Resource": "arn:aws:sqs:REGIAO:CONTA_ID:*"
    },
    {
      "Sid": "AcessoSNS",
      "Effect": "Allow",
      "Action": [ "sns:Publish" ],
      "Resource": "arn:aws:sns:REGIAO:CONTA_ID:*"
    },
    {
      "Sid": "ChavePlataformaEUsuarios",
      "Effect": "Allow",
      "Action": [
        "kms:CreateKey",
        "kms:CreateAlias",
        "kms:Sign",
        "kms:GetPublicKey"
      ],
      "Resource": "*"
    },
    {
      "Sid": "SecretsManager",
      "Effect": "Allow",
      "Action": [ "secretsmanager:GetSecretValue" ],
      "Resource": "arn:aws:secretsmanager:REGIAO:CONTA_ID:secret:/kms-integration/*"
    }
  ]
}
```

> `kms:CreateKey` e `kms:CreateAlias` são necessários tanto para o auto-provisionamento da wallet admin quanto para a criação de wallets de clientes via `ACCOUNT_CREATE`.

---

## VPC Interface Endpoints (ambientes sem internet)

Em ambientes isolados, criar VPC Interface Endpoints para:

| Serviço | Nome do endpoint |
|---|---|
| SQS | `com.amazonaws.REGIAO.sqs` |
| SNS | `com.amazonaws.REGIAO.sns` |
| KMS | `com.amazonaws.REGIAO.kms` |
| Secrets Manager | `com.amazonaws.REGIAO.secretsmanager` |
| ECR API | `com.amazonaws.REGIAO.ecr.api` |
| ECR Docker | `com.amazonaws.REGIAO.ecr.dkr` |
| S3 (camadas ECR) | `com.amazonaws.REGIAO.s3` (Gateway endpoint) |

Habilitar **DNS Privado** em cada endpoint para que o SDK AWS resolva automaticamente para os IPs internos. Nenhuma alteração de código é necessária.

Se o DNS privado **não estiver disponível**, sobrescrever via variável de ambiente:

```bash
AWS_SQS_ENDPOINT=https://vpce-XXXXX.sqs.REGIAO.vpce.amazonaws.com
AWS_SNS_ENDPOINT=https://vpce-XXXXX.sns.REGIAO.vpce.amazonaws.com
```

---

## Variáveis de ambiente

| Variável | Obrigatória | Padrão | Descrição |
|---|---|---|---|
| `DLT_RPC_ENDPOINT` | **sim** | — | URL JSON-RPC do nó blockchain (ex: `http://validador:8545`) |
| `SNS_TOPIC_ARN` | **sim** | — | ARN do tópico SNS FIFO para respostas sem `responseQueue` |
| `DB_PASSWORD` | **sim** | — | Senha do usuário `kms_app` no PostgreSQL |
| `DB_LIQUIBASE_PASSWORD` | **sim** | — | Senha do usuário `dpl_devops_liquibase` |
| `KMS_KEY_ID` | não | — | ARN ou ID da chave KMS da plataforma. Se omitido, é auto-provisionado no primeiro startup |
| `AWS_REGION` | não | `us-east-1` | Região AWS |
| `SQS_QUEUE_NAME` | não | `atoken-integracao-kms-sqs-dev.fifo` | Nome da fila SQS de entrada |
| `DB_HOST` | não | `192.168.15.5` | Host do PostgreSQL |
| `DB_PORT` | não | `5432` | Porta do PostgreSQL |
| `DB_NAME` | não | `kms_db` | Nome do banco de dados |
| `DB_USERNAME` | não | `kms_app` | Usuário da aplicação no PostgreSQL |
| `DB_LIQUIBASE_USER` | não | `dpl_devops_liquibase` | Usuário para migrations Liquibase |
| `DLT_CHAIN_ID` | não | `1337` | Chain ID da rede EVM |
| `DLT_GAS_LIMIT` | não | `10000000` | Limite de gas por transação |
| `DLT_MAX_FEE_PER_GAS` | não | `0` | Máximo fee EIP-1559 (wei); 0 = gas gratuito |
| `DLT_MAX_PRIORITY_FEE_PER_GAS` | não | `0` | Priority fee EIP-1559 (wei) |
| `DLT_GAS_FUND_AMOUNT_ETH` | não | `0.001` | Valor em ETH enviado para novas wallets (fundo de gas) |
| `DLT_EXPLORER_URL` | não | — | URL base do explorador de blocos (opcional, para metadata) |
| `AWS_SQS_ENDPOINT` | não | — | Endpoint SQS alternativo (VPC endpoint ou LocalStack) |
| `AWS_SNS_ENDPOINT` | não | — | Endpoint SNS alternativo |
| `AWS_SECRETSMANAGER_ENDPOINT` | não | — | Endpoint Secrets Manager alternativo |

### Credenciais via AWS Secrets Manager

Em produção, as credenciais do banco de dados podem ser carregadas automaticamente do Secrets Manager. O segredo deve ser um JSON plano no caminho `/kms-integration/rds-credentials`:

```json
{
  "DB_HOST": "...",
  "DB_USERNAME": "kms_app",
  "DB_PASSWORD": "...",
  "DB_LIQUIBASE_PASSWORD": "..."
}
```

---

## Executando com Docker

```bash
docker run -d \
  --name kms-integration \
  --env-file .env \
  -p 8090:8080 \
  --restart unless-stopped \
  CONTA_ID.dkr.ecr.REGIAO.amazonaws.com/kms-integration:latest
```

---

## Ambiente local de testes (LocalStack)

Para rodar localmente sem AWS real, utilize o LocalStack:

```bash
# Iniciar LocalStack
docker run -d --name localstack \
  -p 4566:4566 \
  -e SERVICES=sqs,sns,kms \
  localstack/localstack:3

# Criar fila SQS FIFO
aws --endpoint-url=http://localhost:4566 --no-sign-request \
  sqs create-queue \
  --queue-name atoken-integracao-kms-sqs-dev.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=true

# Criar tópico SNS FIFO
aws --endpoint-url=http://localhost:4566 --no-sign-request \
  sns create-topic \
  --name kms-integration-responses.fifo \
  --attributes FifoTopic=true,ContentBasedDeduplication=true
```

Com LocalStack, **não** é necessário criar a chave KMS manualmente — o serviço a cria automaticamente no startup.

Configurar no `.env`:

```
AWS_ACCESS_KEY_ID=dummy
AWS_SECRET_ACCESS_KEY=dummy
AWS_SQS_ENDPOINT=http://localhost:4566
AWS_SNS_ENDPOINT=http://localhost:4566
AWS_SECRETSMANAGER_ENDPOINT=http://localhost:4566
# KMS_KEY_ID não é necessário — será criado automaticamente
```

---

## Build

```bash
mvn clean package -DskipTests
```

O JAR gerado fica em `target/kms-integration-1.0.0.jar`.

Durante o build, o `web3j-maven-plugin` compila automaticamente os contratos Solidity em `src/main/solidity/` e gera as classes Java com os bytecodes em `target/generated-sources/web3j/`.

---

## Testes

```bash
mvn test
```

Os testes unitários utilizam Mockito — nenhum serviço AWS real é necessário. O teste de integração `CreationConsumerIntegrationTest` requer Docker (inicia um container Ganache automaticamente via Testcontainers).

---

## Wallets por cliente — granularidade e segurança

**1 chave KMS = 1 endereço Ethereum = 1 wallet.**

O endereço Ethereum é derivado da chave pública da chave KMS. A mesma chave funciona em todas as redes EVM (Besu, Sepolia, mainnet etc.) — o `chainId` vai na transação, não na chave.

**O `keyId` (ARN da chave KMS) é interno ao kms-integration e nunca é exposto ao BFF ou ao usuário final.** A resposta do `ACCOUNT_CREATE` retorna apenas o `address` e o `clientId` enviado pelo BFF. O BFF deve armazenar o `address` como identificador da wallet do cliente.

Nas operações de transferência do usuário (`USER_TRANSFER`), o campo `userWalletId` deve conter o `address` (ou o `clientId`, dependendo de como o BFF rastrear a associação internamente). O kms-integration busca o `keyId` correspondente no banco de dados pelo endereço ou pelo clientId para assinar a transação.
