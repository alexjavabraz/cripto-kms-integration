# kms-integration

Blockchain signing service for regulated, air-gapped AWS environments.

Replaces `dfns_integration` (DFNS SaaS) with AWS KMS for Ethereum/EVM transaction signing. Communicates exclusively via **AWS SQS** — no inbound ports, no internet required.

---

## Architecture

```
BFF (Node.js)
  │  publishes request
  ▼
SQS request queue
  │  @SqsListener
  ▼
kms-integration (Java / Spring Boot)
  │  signs via AWS KMS (ECC_SECG_P256K1)
  │  submits tx via RPC
  ▼
Permissioned DLT network
  │  result published
  ▼
SQS response queue
  │  @SqsListener
  ▼
BFF (Node.js)
```

All AWS services (SQS, KMS, ECR) are accessed via **VPC Interface Endpoints** — no traffic leaves the client's VPC.

---

## SQS Queues

Twelve queues are required. Standard queues are sufficient; FIFO queues are not needed.

| Queue name (default) | Direction | Consumer |
|---|---|---|
| `kms-token-creation-request` | BFF → kms-integration | `CreationConsumer` |
| `kms-token-creation-response` | kms-integration → BFF | BFF listener |
| `kms-balance-request` | BFF → kms-integration | `BalanceConsumer` |
| `kms-balance-response` | kms-integration → BFF | BFF listener |
| `kms-token-event-request` | BFF → kms-integration | `TokenEventConsumer` |
| `kms-token-event-response` | kms-integration → BFF | BFF listener |
| `kms-token-transfer-request` | BFF → kms-integration | `TransferConsumer` |
| `kms-token-transfer-response` | kms-integration → BFF | BFF listener |
| `kms-account-create-request` | BFF → kms-integration | `AccountConsumer` |
| `kms-account-create-response` | kms-integration → BFF | BFF listener |
| `kms-user-transfer-request` | BFF → kms-integration | `UserTransferConsumer` |
| `kms-user-transfer-response` | kms-integration → BFF | BFF listener |

### Create all queues (AWS CLI)

```bash
REGION=us-east-1
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

QUEUES=(
  kms-token-creation-request
  kms-token-creation-response
  kms-balance-request
  kms-balance-response
  kms-token-event-request
  kms-token-event-response
  kms-token-transfer-request
  kms-token-transfer-response
  kms-account-create-request
  kms-account-create-response
  kms-user-transfer-request
  kms-user-transfer-response
)

for Q in "${QUEUES[@]}"; do
  aws sqs create-queue \
    --queue-name "$Q" \
    --attributes '{
      "VisibilityTimeout": "300",
      "MessageRetentionPeriod": "86400",
      "ReceiveMessageWaitTimeSeconds": "20"
    }' \
    --region "$REGION"
  echo "Created: $Q"
done
```

Recommended attributes:
- `VisibilityTimeout`: 300 s (5 min) — allows time for tx mining + receipt polling (up to 2 min)
- `MessageRetentionPeriod`: 86400 s (1 day)
- `ReceiveMessageWaitTimeSeconds`: 20 s (long polling — reduces empty receives)

---

## IAM Permissions

The EC2 instance (or ECS task) running `kms-integration` needs the following IAM policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SqsAccess",
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes",
        "sqs:SendMessage"
      ],
      "Resource": "arn:aws:sqs:REGION:ACCOUNT_ID:kms-*"
    },
    {
      "Sid": "KmsSigningKey",
      "Effect": "Allow",
      "Action": [
        "kms:Sign",
        "kms:GetPublicKey"
      ],
      "Resource": "arn:aws:kms:REGION:ACCOUNT_ID:key/PLATFORM_KEY_ID"
    },
    {
      "Sid": "KmsUserWallets",
      "Effect": "Allow",
      "Action": [
        "kms:CreateKey",
        "kms:CreateAlias",
        "kms:Sign",
        "kms:GetPublicKey"
      ],
      "Resource": "*"
    }
  ]
}
```

> **Note**: Scope `KmsUserWallets` to a specific key policy or tag condition in production to restrict wallet creation permissions.

---

## VPC Interface Endpoints (air-gapped environments)

In environments without internet access, create VPC Interface Endpoints for:

| Service | Endpoint service name |
|---|---|
| SQS | `com.amazonaws.REGION.sqs` |
| KMS | `com.amazonaws.REGION.kms` |
| ECR API | `com.amazonaws.REGION.ecr.api` |
| ECR Docker | `com.amazonaws.REGION.ecr.dkr` |
| S3 (ECR layers) | `com.amazonaws.REGION.s3` (Gateway endpoint) |
| CloudWatch Logs | `com.amazonaws.REGION.logs` |

Enable **Private DNS** on each interface endpoint so the standard AWS SDK endpoint URLs resolve to the private IPs automatically. No code changes are needed.

If Private DNS is **not** available, override the SQS endpoint via env var:

```bash
AWS_SQS_ENDPOINT=https://vpce-XXXXX.sqs.REGION.vpce.amazonaws.com
```

---

## KMS Key Setup

### Platform signing key (one per deployment)

```bash
# Create the platform key
KEY_ID=$(aws kms create-key \
  --key-spec ECC_SECG_P256K1 \
  --key-usage SIGN_VERIFY \
  --description "Tokeniza platform signing key" \
  --query KeyMetadata.KeyId --output text)

# Add alias
aws kms create-alias \
  --alias-name alias/tokeniza-platform \
  --target-key-id "$KEY_ID"

echo "KMS_KEY_ID=$KEY_ID"
```

### User wallet keys

Created automatically by `AccountConsumer` when the BFF publishes an `account-create-request`. Each user gets a dedicated `ECC_SECG_P256K1 / SIGN_VERIFY` key with alias `alias/tokeniza-user-{userId}`. The Key ID is stored as `walletId` in the BFF's MongoDB.

---

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `KMS_KEY_ID` | **yes** | — | Platform KMS key ARN or ID |
| `DLT_RPC_ENDPOINT` | **yes** | — | Blockchain JSON-RPC URL (e.g. `http://validator:8545`) |
| `AWS_REGION` | no | `us-east-1` | AWS region |
| `DLT_CHAIN_ID` | no | `1337` | EVM chain ID |
| `DLT_GAS_LIMIT` | no | `300000` | Gas limit per transaction |
| `DLT_MAX_FEE_PER_GAS` | no | `0` | EIP-1559 max fee (wei); 0 = free gas |
| `DLT_MAX_PRIORITY_FEE_PER_GAS` | no | `0` | EIP-1559 priority fee (wei) |
| `DLT_EXPLORER_URL` | no | — | Block explorer base URL (optional, for response metadata) |
| `DLT_GAS_FUND_AMOUNT_ETH` | no | `0.001` | Native token amount sent to new wallets |
| `AWS_SQS_ENDPOINT` | no | — | Override SQS endpoint (VPC endpoint URL) |
| `SQS_TOKEN_CREATION_REQUEST` | no | `kms-token-creation-request` | Queue name override |
| `SQS_TOKEN_CREATION_RESPONSE` | no | `kms-token-creation-response` | Queue name override |
| `SQS_BALANCE_REQUEST` | no | `kms-balance-request` | Queue name override |
| `SQS_BALANCE_RESPONSE` | no | `kms-balance-response` | Queue name override |
| `SQS_TOKEN_EVENT_REQUEST` | no | `kms-token-event-request` | Queue name override |
| `SQS_TOKEN_EVENT_RESPONSE` | no | `kms-token-event-response` | Queue name override |
| `SQS_TOKEN_TRANSFER_REQUEST` | no | `kms-token-transfer-request` | Queue name override |
| `SQS_TOKEN_TRANSFER_RESPONSE` | no | `kms-token-transfer-response` | Queue name override |
| `SQS_ACCOUNT_CREATE_REQUEST` | no | `kms-account-create-request` | Queue name override |
| `SQS_ACCOUNT_CREATE_RESPONSE` | no | `kms-account-create-response` | Queue name override |
| `SQS_USER_TRANSFER_REQUEST` | no | `kms-user-transfer-request` | Queue name override |
| `SQS_USER_TRANSFER_RESPONSE` | no | `kms-user-transfer-response` | Queue name override |
| `BYTECODE_ERC20` | conditional | — | Compiled ERC-20 bytecode (required for token deployment) |
| `BYTECODE_ERC721` | conditional | — | Compiled ERC-721 bytecode |
| `BYTECODE_ERC1155` | conditional | — | Compiled ERC-1155 bytecode |
| `SENTRY_DSN` | no | — | Sentry DSN for error tracking |

---

## Running with Docker

```bash
docker run -d \
  --name kms-integration \
  --env-file .env \
  --restart unless-stopped \
  ACCOUNT_ID.dkr.ecr.REGION.amazonaws.com/kms-integration:latest
```

The container has no exposed ports. All communication is outbound via SQS.

---

## Building

```bash
mvn clean package -DskipTests
```

The resulting JAR is at `target/kms-integration-1.0.0.jar`. The `spring-boot-maven-plugin` produces a self-contained executable JAR.

---

## Running Tests

```bash
mvn test
```

Tests use Mockito — no running AWS services are required.
