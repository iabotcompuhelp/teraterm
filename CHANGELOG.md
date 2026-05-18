# Changelog

## 1.0.1 — 2026-05-17

Cleanup post-T1 pre-flight (Phase 2.5). Cierra hallazgos de la validación humana antes de
arrancar Stage B (testing con Claude Desktop / Cursor / Cline). No agrega features ni cambia
la API de las 10 tools del MCP — solo bugs y mejoras de UX.

### Fixes

- **Versión del MCP server reportada correctamente** en `/mcp/health`, evento SSE `ready` y
  resultado de `initialize`. Antes quedaba hardcoded en `0.1.0` aunque la app estuviera en
  1.0.0+. Ahora `BuildInfo.VERSION` resuelve con 3 fallbacks: manifest del JAR
  (`Implementation-Version`) → resource `mcp-build-info.properties` generado por Gradle →
  `"unknown"`. (Phase 2.5 T1, commit `a50643a`)
- **Defaults SSH amplios para equipos enterprise/FIPS** — la lista KEX pasó de 2 a 8
  algoritmos (agrega `curve25519-sha256@libssh.org`, `ecdh-sha2-nistp256/384/521`,
  `dh-group16-sha512`, `dh-group14-sha256`), ciphers de 5 a 8 (`aes192-ctr`, `aes256-gcm` y
  `aes128-gcm` sin sufijo `@openssh.com`), MACs de 2 a 5 (`hmac-sha2-256`, `hmac-sha2-512`,
  `hmac-sha1`). Cubre Cisco IOS reciente y hardware federal sin obligar al operador a editar
  settings. Usuarios con listas persistidas las conservan (Jackson respeta el JSON existente).
  (Phase 2.5 T2, commit `e47678a`)
- **Telnet `EchoOptionHandler` invertido** de `(true,false,true,false)` a
  `(false,true,false,true)`. El handler anterior asumía "yo soy el terminal local que hace
  eco", pero el server SIEMPRE hace eco en CLI de equipos de red. Síntoma reproducido contra
  3Com Baseline 2928: `Username:aaddmmiinn`, password visible en plaintext, comandos
  duplicados. Tras el fix sólo el server hace echo y el password se oculta automáticamente.
  (Phase 2.5 T3, commit `b28ccc8`)
- **Error dialog SSH con tip accionable y atajo a Setup** — al fallar una negociación
  (`Algorithm negotiation fail` con `algorithmName=kex/cipher/mac/host_key` o un `UnknownHostKey`)
  el dialog ahora muestra un mensaje user-friendly arriba del bloque técnico y agrega un
  botón "Abrir configuración SSH" que cierra el dialog y abre Setup → SSH General.
  (Phase 2.5 T4, commit `5a11544`)

### Mejoras de diagnóstico

- **Toggle "Telnet: log detallado de negociación IAC"** en Setup → Additional → Log. Cuando
  está activo, el cliente Telnet registra un `spyStream` a stderr que loguea
  WILL/WONT/DO/DONT y sub-negociaciones — útil para diagnosticar fallas de negociación
  futuras sin tener que rebuildear con instrumentación ad-hoc. Off por default.
  (Phase 2.5 T3, commit `b28ccc8`)

### Docs

- **Sección Troubleshooting** en `docs/mcp/USER_GUIDE.md` y `USER_GUIDE.en.md` con los 4
  hallazgos recurrentes del pre-flight de T1: encoding PowerShell (`Ã³`/`Ã­`), curl+JSON con
  backslashes literales (`Invoke-RestMethod` vs `curl.exe --%`), respuesta `-32600 Cliente
  no inicializado` (danza `initialize → tools/list`), Claude Desktop con proceso residual.
  Cada sub-sección lleva snippet copy-paste. (Phase 2.5 T5, commit `7c2bf4f`)

### Tests de regresión

- `McpServerVersionTest` (4 asserts): semver válido, no es `0.1.0`, no es `unknown`,
  `initialize` propaga la versión real.
- `SshGeneralDefaultsTest` (6 asserts): kex incluye `ecdh-sha2-nistp256`, ciphers y MACs
  cubren los algoritmos esperados, roundtrip JSON respeta la lista legacy del usuario.
- `TelnetOptionHandlerTest` (2 asserts): flags exactos del `EchoOptionHandler` tras el
  invert + orden de los 4 handlers registrados.
- `SshErrorTipTest` (10 asserts): cada tipo (KEX/CIPHER/MAC/HOSTKEY_NEGOTIATION/
  HOSTKEY_UNKNOWN_OR_CHANGED) + null + cause chain + flags `opensSshGeneral` + fallback
  heurístico cuando no hay `algorithmName=` parseable.

### Race condition fix bonus (out of phase)

- **Data race en `TelnetView.appendBytes`** que arrastraba el doble prompt + tab garbled en
  SSH. El reader de `SshConnection.readLoop` reusa el mismo `byte[]` en cada `read()`; el
  closure pasado a `runOnFx` decodificaba el `String` DESPUÉS de que el reader ya había
  sobrescrito los bytes. Fix: snapshot sincrónico (`String(data, 0, n, cs)` o
  `data.copyOfRange`) ANTES del `runOnFx`. (commit `aeb3639`)

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