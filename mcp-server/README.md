# `mcp-server` — Servidor MCP de OpenTermX

Este módulo expone las capacidades de OpenTermX (sesiones SSH/Telnet/Serial activas,
base de conocimiento RAG y propuesta de comandos con aprobación humana) como **tools del
Model Context Protocol (MCP)**. Permite que un cliente externo —Claude Desktop, Cursor,
Claude Code, Continue.dev, Cline— opere OpenTermX usando el LLM y la sesión del cliente,
sin que OpenTermX necesite credenciales de ningún proveedor de IA para esta función.

> OpenTermX **NO consume LLMs en este módulo**: solo expone tools y responde a llamadas.

## Cómo habilitarlo

1. `Setup → AI Assistant…` y abrí la pestaña **MCP Server**.
2. Marcá *Enable MCP server*.
3. Dejá el puerto en `8765` (o cambialo si está ocupado) y el bind en `127.0.0.1`.
4. Opcional pero recomendado: generá un **token** (botón *Generate*).
5. Aceptá el diálogo. La status bar muestra `🧠 MCP :127.0.0.1:8765` cuando arrancó.
6. Copiá el snippet de la sección *Snippet para `claude_desktop_config.json`* y pegalo en
   la configuración de tu cliente.

> El servidor también se apaga automáticamente al cerrar OpenTermX (shutdown hook).

## Notas de seguridad

- **Bind por defecto `127.0.0.1`** — sólo conexiones locales. Cambiarlo a `0.0.0.0`
  expone el servidor a la LAN: la UI muestra un warning rojo si lo hacés y, salvo que
  sepas exactamente qué hacés, deberías combinarlo con un firewall y un token.
- **Token bearer opcional** — si está seteado en settings, *todo* request (incluido
  `/mcp/health`) debe traer `Authorization: Bearer <token>`. Si lo dejás vacío, no hay
  auth: úsalo sólo con bind loopback.
- **`propose_commands` siempre pasa por el panel de revisión** con semáforo de riesgo
  (verde/amarillo/rojo). Esta invariante NO se negocia: la IA NUNCA inyecta comandos sin
  tu confirmación, ni siquiera cuando son SAFE.
- **Auditoría** — cada llamada a `propose_commands` queda registrada en
  `~/.opentermx/audit-ia.csv` con timestamp, sesión, vendor, comandos, contadores y
  decisión del operador.

## Tools expuestas

### `list_sessions` (read-only)

Devuelve las sesiones de terminal activas en OpenTermX.

```jsonc
// Input
{}
// Output
{
  "sessions": [
    {
      "sessionId": "session-cisco",
      "protocol": "SSH",
      "host": "router-cisco.lab",
      "port": 22,
      "username": "admin",
      "vendor": "Cisco IOS"
    }
  ]
}
```

### `inspect_session` (read-only)

Devuelve la metadata y las últimas N líneas del buffer de una sesión activa.

```jsonc
// Input
{ "sessionId": "session-cisco", "lastLines": 50 }
// Output
{
  "sessionId": "session-cisco",
  "protocol": "SSH",
  "host": "router-cisco.lab",
  "vendor": "Cisco IOS",
  "lines": ["Cisco IOS Software, Version 15.2(4)E10", "router>"]
}
```

`lastLines` está entre 1 y 500 (default 50).

### `search_knowledge_base` (read-only)

Busca chunks relevantes en la base de conocimiento Lucene de OpenTermX.

```jsonc
// Input
{ "query": "VLAN management", "topK": 5 }
// Output
{
  "hits": [
    {
      "source": "C:\\…\\policies.md",
      "chunkIndex": 3,
      "text": "La VLAN 10 es para gestión…",
      "score": 1.42
    }
  ]
}
```

`topK` entre 1 y 20 (default 5). Si la KB no está inicializada o el índice está vacío,
devuelve `hits: []`.

### `propose_commands` (mutativa — requiere aprobación humana)

Propone comandos para inyectar en una sesión activa. Abre el panel de revisión con
semáforo de riesgo SAFE/CONFIG/DANGEROUS y sólo ejecuta los comandos aprobados por el
operador. La respuesta resume qué se aprobó, ejecutó y descartó.

```jsonc
// Input
{
  "sessionId": "session-cisco",
  "commands": ["show version", "configure terminal", "interface Gi0/1", "no shutdown"],
  "rationale": "Habilitar interfaz Gi0/1 después del corte"
}
// Output
{
  "approved": true,
  "executed": 4,
  "rejected": 0,
  "auditLogId": "8f4f1c…",
  "output": "interface Gi0/1\nno shutdown\nrouter(config-if)#…",
  "riskSummary": { "safe": 1, "config": 3, "dangerous": 0 }
}
```

Si el operador rechaza: `approved: false`, `executed: 0`, `rejected: <total>`.

### `run_readonly_command` (read-only ejecutable — sin aprobación humana)

Fase 1 del plan de telemetría. Ejecuta **un** comando de solo lectura en una sesión
activa, sin gate humano: la seguridad la garantiza una **whitelist regex por vendor**
(`show`/`display`/`get`/`diagnose sys|hardware|netlink`, `ping`, `traceroute`,
`/… print`), editable en `~/.opentermx/policies/readonly-commands.yaml` (botón
*Editar whitelist…* del Setup; default embebido en el jar). Todo lo que no matchea se
rechaza: metacaracteres, encadenadores, pipes a `redirect`/`tee`/`save`/`copy`,
comandos de más de 512 chars, y **vendor no detectado** (no se adivina).

El runner detecta el prompt del equipo (regex por vendor), des-pagina una vez por
sesión (`terminal length 0`, `screen-length 0 temporary`, `no page`; MikroTik via
`without-paging`; fallback: auto-respuesta de espacio ante `--More--`), serializa
comandos concurrentes con un mutex por sesión, y al expirar el timeout devuelve el
output parcial con `timedOut: true` — nunca cuelga al cliente.

```jsonc
// Input
{ "sessionId": "session-cisco", "command": "show interfaces | include errors", "timeoutSeconds": 15 }
// Output
{
  "sessionId": "session-cisco",
  "command": "show interfaces | include errors",
  "vendor": "Cisco IOS",
  "approved": true,
  "output": "  0 input errors, 0 CRC…",
  "truncated": false,
  "timedOut": false,
  "durationMs": 842,
  "auditLogId": "1c9a4f…"
}
```

El checkbox *Allow read-only commands without approval* (default ON) controla el modo:
apagado, cada comando vuelve a pasar por el diálogo de aprobación. Toda invocación —
incluso los rechazos de la whitelist — queda en `~/.opentermx/audit-ia.csv`.

## Configuración del cliente

### Claude Desktop / Cursor / Claude Code

Pegá el snippet generado por la UI (Setup → AI Assistant → MCP Server → *Copiar snippet*).
La forma es:

```json
{
  "mcpServers": {
    "opentermx": {
      "url": "http://127.0.0.1:8765/mcp",
      "headers": { "Authorization": "Bearer TU_TOKEN" }
    }
  }
}
```

Reiniciá el cliente para que recargue la configuración. Las tools aparecerán bajo el
servidor `opentermx`.

### Cliente HTTP genérico

El servidor habla JSON-RPC 2.0 sobre `POST /mcp`. Para discovery: `GET /mcp/health`
devuelve `{status, tools, server, version}`. SSE complementario en `GET /mcp/sse` (no
emite eventos todavía, está reservado para futuras notificaciones de cambio de sesiones).

## Endpoints

| Método | Path           | Función                                                |
|--------|----------------|--------------------------------------------------------|
| POST   | `/mcp`         | JSON-RPC 2.0 (initialize, tools/list, tools/call, ping) |
| GET    | `/mcp/sse`     | Server-sent events (canal de notifications)            |
| GET    | `/mcp/health`  | `{status, tools, server, version}`                     |

## Estructura

```
mcp-server/
├── src/main/kotlin/com/opentermx/mcp/
│   ├── McpServer.kt              # Bootstrap HTTP/SSE + StateFlow de estado
│   ├── protocol/
│   │   ├── JsonRpc.kt            # Request/Response/Error JSON-RPC 2.0
│   │   └── McpDispatcher.kt      # Dispatch initialize/tools-list/tools-call
│   ├── tools/
│   │   └── ToolDefinitions.kt    # Catálogo + JSON Schemas de las 4 tools
│   ├── handlers/
│   │   ├── ListSessionsHandler.kt
│   │   ├── InspectSessionHandler.kt
│   │   ├── SearchKnowledgeBaseHandler.kt
│   │   └── ProposeCommandsHandler.kt
│   └── security/
│       └── ApprovalGate.kt       # Interfaz JavaFX-independiente; impl en app/
└── src/test/
    ├── kotlin/                    # Unit tests (JUnit 5 + mocks)
    │   └── com/opentermx/mcp/
    │       ├── handlers/         # Tests de los 4 handlers
    │       ├── protocol/         # Tests del dispatcher
    │       └── testserver/       # TestServerMain para integration tests
    └── python/                    # Tests de integración (pytest + httpx)
        ├── conftest.py            # Fixture que spawnea TestServerMain
        ├── test_protocol.py
        ├── test_tools.py
        ├── test_auth.py
        └── test_errors.py
```

## Build y tests

```bash
# Unit tests (Kotlin/JUnit5):
./gradlew :mcp-server:test

# Integration tests (pytest + httpx) — crea venv automáticamente:
./gradlew :mcp-server:pythonTests

# Ambos via check:
./gradlew :mcp-server:check
```

Los integration tests piden Python 3.10+ y una `JAVA_HOME` válida. El servidor de tests
levanta en puerto libre con `OPENTERMX_TEST_AUTO_APPROVE=1`, lo que permite ejercitar
`propose_commands` sin requerir intervención humana.

## Decisiones arquitectónicas

- **Kotlin para el núcleo (no negociable)** — necesita acceso en proceso a
  `SessionRegistry` (sockets NIO vivos), `KnowledgeBase` (Lucene en memoria) y al
  `AiExecuteApprovalDialog` (JavaFX). IPC + serialización rompería el contrato.
- **Javalin** como transporte HTTP/SSE, consistente con el módulo `rest-api`.
- **JSON-RPC 2.0 manual** sobre Jackson (no se usa el transport del SDK MCP) — evita
  arrastrar la stack Ktor entera en runtime y mantiene el código simple.
- **`ApprovalGate` como interfaz** en `mcp-server` con implementación JavaFX en `app`:
  el módulo no depende de JavaFX, lo que hace los tests rápidos y desacopla la UI.
- **Python para integration tests** — pytest + httpx es más legible y con menos
  boilerplate que OkHttp + JUnit para validar contratos HTTP + JSON Schema black-box.