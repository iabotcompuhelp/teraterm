# Changelog

## 1.0.0 — 2026-05-15

Primera release pública. Incorpora el módulo `mcp-server` completo con todas las features
de Phase 1 y Phase 2 listas para uso real con clientes MCP públicos.

### Módulo nuevo: `mcp-server`

- Servidor JSON-RPC 2.0 sobre HTTP/SSE en `127.0.0.1:8765` por default. Implementado en Kotlin
  + Javalin, sin dependencia de Ktor en runtime.
- Transporte alterno **stdio proxy** (`StdioProxyMain`) para clientes que no soportan HTTP.
  La UI instala el wrapper en `~/.opentermx/bin/`.
- Negociación de **MCP-Protocol-Version** con soporte para `2024-11-05` y `2025-03-26`.

### Tools expuestas (10 total)

Read-only:

- `list_sessions` — sesiones SSH/Telnet/Serial activas con vendor detectado.
- `inspect_session` — metadata + últimas N líneas del buffer (con redacción).
- `search_knowledge_base` — chunks Lucene RAG por query.
- `list_macros` — macros Groovy registrados en `~/.opentermx/macros/`.
- `read_audit_log` — entradas del audit log (con redacción aplicada).

Mutativas (pasan SIEMPRE por aprobación del operador):

- `propose_commands` — sugiere comandos con clasificación de riesgo SAFE/CONFIG/DANGEROUS.
- `run_macro` — ejecuta un macro Groovy mostrando el código antes.
- `open_session` — abre una sesión nueva al destino indicado.
- `close_session` — cierra una sesión activa con razón opcional.

Side-channel:

- `tail_session` — stream SSE de output en vivo, auto-stop a los 30 min.

### Resources y Prompts

- `resources/list` + `resources/read` con:
  - `opentermx://audit-log` — audit log completo (con redacción).
  - `opentermx://sessions` — JSON con la lista de sesiones.
- `prompts/list` + `prompts/get` con:
  - `diagnose_connectivity` (sessionId).
  - `audit_recent_changes` (hours).

### Seguridad

- **Redacción de credenciales** built-in: enable secret / password, SNMP community,
  TACACS/RADIUS keys, Bearer headers, bloques PEM. Reglas custom configurables.
- **Multi-token** con hash SHA-256 (plaintext nunca persistido), scope (FULL / READ_ONLY)
  y expiry opcional. Migración automática del token legacy single-string.
- **Rate limiting** con token bucket (60 req/min, burst 20) + circuit breaker por tool
  (5 rechazos consecutivos → ban 10 min con error JSON-RPC -32603).
- **TLS opcional** vía SslPlugin de Javalin con keystore JKS/PKCS12.
- **ACL por sessionId** con globs (`lab-*,test-?`).
- **Read-only mode** que short-circuit todas las tools mutativas.
- **Audit log** en `~/.opentermx/audit-ia.csv` (append-only) con redacción para reads MCP.

### Notifications SSE

- `notifications/sessions/changed` — sesiones abiertas/cerradas.
- `notifications/sessions/output` — output en vivo (solo si hay `tail_session` activo).
- `notifications/audit/appended` — cada entrada nueva del audit (con redacción).

### Build & distribución

- Tasks `jpackageMac`, `jpackageLinuxDeb`, `jpackageLinuxRpm` además del `packageMsi`
  existente. Incluyen JRE empaquetado vía jlink.
- Task `installStdioProxy` para generar el wrapper de stdio en dev.
- Tests: 50+ unit tests Kotlin (JUnit 5) y 22 integration tests Python (pytest + httpx).
- Pipeline de redaction-aware logging con verbose mode opcional.

### Tooling

- `tools/mcp/smoke_test.py` — health check + tool round-trip < 2s.
- `tools/mcp/generate_docs.py` — autogen de la sección Tools del README.
- `tools/mcp/schema_diff.py` — diff de schemas entre versiones, gates breaking changes.

### Docs

- `mcp-server/README.md` (ES) — overview + tools reference.
- `docs/mcp/USER_GUIDE.md` + `USER_GUIDE.en.md` — quickstarts por cliente, troubleshooting.
- `docs/mcp/CLIENT_TEST_PLAN.md` — checklist de validación humana.
- `docs/release/SIGNING.md` + `RELEASE_CHECKLIST.md` — procedimiento de release.

### Compatibilidad

- Las 4 tools originales de Phase 1 (`list_sessions`, `inspect_session`,
  `search_knowledge_base`, `propose_commands`) mantienen schemas inalterados.
- Settings legacy (`mcpServerToken: EncryptedValue?`) migra automáticamente a `mcpServerTokens`
  en el primer `applySettings` después del upgrade.