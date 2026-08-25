#!/usr/bin/env sh
set -eu

# Prepara secretos locales para el laboratorio. Nunca reemplaza secretos existentes.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SECRETS_DIR="$SCRIPT_DIR/secrets"
AGENT_ID=${OPENTERMX_LAB_AGENT_ID:-noc-win-01}

command -v docker >/dev/null 2>&1 || {
  echo "ERROR: Docker no esta instalado o no esta en PATH." >&2
  exit 1
}
docker compose version >/dev/null 2>&1 || {
  echo "ERROR: se requiere Docker Compose v2 (docker compose)." >&2
  exit 1
}
command -v openssl >/dev/null 2>&1 || {
  echo "ERROR: OpenSSL no esta instalado." >&2
  exit 1
}

umask 077
mkdir -p "$SECRETS_DIR"

create_secret() {
  target=$1
  if [ ! -s "$target" ]; then
    openssl rand -base64 32 > "$target"
    echo "Creado: $target"
  else
    echo "Conservado: $target"
  fi
}

create_secret "$SECRETS_DIR/db_password.txt"
create_secret "$SECRETS_DIR/mcp_token.txt"

AGENT_FILE="$SECRETS_DIR/agent_credentials.properties"
if [ ! -s "$AGENT_FILE" ]; then
  AGENT_TOKEN=$(openssl rand -base64 32)
  printf '%s=%s\n' "$AGENT_ID" "$AGENT_TOKEN" > "$AGENT_FILE"
  echo "Creado: $AGENT_FILE"
  echo "Token inicial para el agente '$AGENT_ID':"
  printf '%s\n' "$AGENT_TOKEN"
  echo "Guardalo ahora en un gestor de secretos; no vuelve a mostrarse automaticamente."
else
  echo "Conservado: $AGENT_FILE"
fi

chmod 700 "$SECRETS_DIR"
chmod 600 "$SECRETS_DIR"/*

cd "$SCRIPT_DIR"
docker compose config --quiet

echo
echo "Preparacion completada. Para iniciar:"
echo "  cd $SCRIPT_DIR"
echo "  docker compose up --build -d"
echo "  docker compose ps"
echo "  curl --fail http://127.0.0.1:8765/mcp/health"
