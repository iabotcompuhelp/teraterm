# Plano de control en Docker

PostgreSQL vive únicamente en la red interna `database`: no publica el puerto 5432 y solamente
el plano de control puede alcanzarlo. El agente Windows conoce la URL HTTPS/VPN y su token, nunca
el host, usuario o contraseña de PostgreSQL.

En el host Linux:

```bash
cd deploy/docker
mkdir -m 700 secrets
openssl rand -base64 32 > secrets/db_password.txt
openssl rand -base64 32 > secrets/mcp_token.txt
AGENT_TOKEN="$(openssl rand -base64 32)"
printf 'noc-win-01=%s\n' "$AGENT_TOKEN" > secrets/agent_credentials.properties
printf 'Token inicial de noc-win-01: %s\n' "$AGENT_TOKEN"
chmod 600 secrets/*
docker compose up --build -d
docker compose ps
curl http://127.0.0.1:8765/mcp/health
```

Los puertos se enlazan sólo a loopback. Publique 8765/8766 mediante un reverse proxy con TLS o
una VPN; no cambie el binding a `0.0.0.0` sin firewall. Flyway aplica automáticamente las
migraciones cuando inicia el servidor. Si PostgreSQL no está saludable, el servidor no inicia.

El endpoint `POST /graphql` comparte el mismo bearer token del plano de control. Ofrece consultas
read-only para `devices`, `deviceActivity`, `agents` y `remoteTasks`. Las tareas no exponen comandos,
resultados ni razones; la ejecución continúa exclusivamente por el flujo MCP auditado y aprobado.

Cada línea de `agent_credentials.properties` representa un agente y su token exclusivo. Para
revocar uno, elimine su línea; para rotarlo, sustituya el valor. El servidor relee el archivo al
cambiar; con secretos de Docker ejecute `docker compose up -d --force-recreate control-plane` para
garantizar que el archivo montado se actualice. El `agentId` de Windows debe coincidir con la clave.
PostgreSQL conserva identidad, plataforma, versión, IP observada, primera/última conexión y eventos
de autenticación; nunca almacena el token. GraphQL expone el histórico con el indicador `online`.

```bash
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  --data '{"query":"{ agents { agentId displayName sessionCount } }"}' \
  http://127.0.0.1:8765/graphql
```

Para respaldo, copie mediante herramientas de PostgreSQL el volumen `postgres-data` y conserve
también `control-plane-data`. No incluya `secrets/` en respaldos sin cifrado.
