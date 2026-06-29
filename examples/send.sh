#!/usr/bin/env bash
# Envia um exemplo de mensagem para a fila SQS (LocalStack ou AWS real).
#
# Uso:
#   ./send.sh <arquivo.json>
#   ./send.sh 04_mint.json
#
# Variáveis de ambiente (opcional — valores default para LocalStack local):
#   SQS_ENDPOINT   URL do endpoint SQS  (default: http://192.168.15.5:4566)
#   SQS_QUEUE_URL  URL completa da fila  (default: LocalStack FIFO)
#   AWS_REGION     Região AWS            (default: us-east-1)

set -euo pipefail

FILE="${1:-}"
if [[ -z "$FILE" ]]; then
  echo "Uso: $0 <arquivo.json>"
  echo ""
  echo "Exemplos disponíveis:"
  ls "$(dirname "$0")"/*.json | xargs -I{} basename {}
  exit 1
fi

# Resolve path relativo ao diretório do script
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FILE_PATH="${SCRIPT_DIR}/${FILE}"
if [[ ! -f "$FILE_PATH" ]]; then
  FILE_PATH="$FILE"
fi
if [[ ! -f "$FILE_PATH" ]]; then
  echo "Arquivo não encontrado: $FILE"
  exit 1
fi

ENDPOINT="${SQS_ENDPOINT:-http://192.168.15.5:4566}"
QUEUE_URL="${SQS_QUEUE_URL:-http://192.168.15.5:4566/000000000000/atoken-integracao-kms-sqs-dev.fifo}"
REGION="${AWS_REGION:-us-east-1}"

BODY=$(cat "$FILE_PATH")
TYPE=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('type','UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
GROUP_ID="send-sh-$(date +%s)"

echo "Enviando $FILE → $QUEUE_URL"
echo "  type=$TYPE  group=$GROUP_ID"
echo ""

aws --endpoint-url="$ENDPOINT" \
    --region "$REGION" \
    --no-sign-request \
    sqs send-message \
    --queue-url "$QUEUE_URL" \
    --message-group-id "$GROUP_ID" \
    --message-body "$BODY"

echo ""
echo "OK — mensagem entregue."
