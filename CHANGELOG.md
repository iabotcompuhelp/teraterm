# Changelog

## Unreleased — Telemetría Fase 3 (2026-06-10)

Persistencia PostgreSQL (catálogo MCP: **29 tools**). Módulo nuevo `telemetry-db`:
HikariCP + Flyway + JDBC explícito, sin ORM.

- Esquema `opentermx` completo (V1__init.sql): devices/interfaces/sessions_log,
  `command_audit` (sucesor del CSV), `config_snapshots`+`config_diffs` con
  **sanitización de secretos** (valor → `<REDACTED>`; sha256 sobre el texto ya
  sanitizado), `interface_metrics` particionada por mes (creación on-demand +
  retry ante `no partition of relation`), `link_events`, tablas de integraciones
  (Fase 4) y vista `v_latest_interface_status`.
- **Degradación con gracia**: sin BD, las tools de telemetría siguen funcionando
  (`persisted: false`) y solo `get_device_history` (tool nueva) devuelve
  `DB_UNAVAILABLE`. `get_interface_stats` con `persist: true` inserta la muestra y
  detecta transiciones de enlace.
- Scheduler de muestreo opcional sobre sesiones ACTIVAS (no abre sesiones: el
  approval gate de `open_session` no se negocia): semáforo de 5, backoff exponencial
  5→10→20→60 min tras 3 fallos, mantenimiento diario de particiones (pre-crear mes
  próximo, retención default 90 días).
- Import one-shot **idempotente** del `audit-ia.csv` legacy (`legacy_row_hash`
  sha256 por fila) — corre automáticamente al conectar la BD.
- Config en settings (`database`): host default `localhost` (placeholder), password
  cifrado con SecretCipher o env `OPENTERMX_DB_PASSWORD`; nunca plaintext. El
  `SessionCommandRunner` pasa a ser único por proceso (compartido entre MCP server y
  scheduler — el mutex por sesión exige una sola instancia).
- Tests contra **PostgreSQL real embebido** (binarios zonky, sin Docker): migración
  limpia/idempotente, roundtrip con particiones y vista, sanitización, import
  idempotente, transición de enlace vía scheduler, `DB_UNAVAILABLE`.

## Unreleased — Telemetría Fase 2 (2026-06-10)

Parsing estructurado por vendor + tools de telemetría de alto nivel (catálogo MCP:
**28 tools**).

- Módulo nuevo `net-parsers/` (Kotlin puro): modelo canónico `InterfaceStats` (18
  campos, contadores Long), `ParseResult` Success/PartialSuccess/Failure, y 8 parsers:
  Cisco IOS/IOS-XE (`show interfaces`), NX-OS (`show interface`), Huawei VRP
  (`display interface`), Aruba AOS-CX (`show interface`), FortiOS (`get system
  interface` + `diagnose hardware deviceinfo nic`), MikroTik (`/interface print
  stats` + `ethernet monitor`). Regla de oro: campo ausente ⇒ null, basura ⇒ Failure
  con muestra del crudo — ningún parser lanza.
- 24 fixtures reales/realistas (12 pares .txt/.expected.json en 6 vendors) comparados
  con JSONAssert STRICT + fixtures `_dirty/` de stream crudo (ESC, backspaces,
  Latin-1 inválido, paginadores, syslog) para el `OutputCleaner`, que se mudó de
  `mcp-server` a `net-parsers` y ganó decode tolerante, filtro de syslog y descarte
  de prompt final.
- 3 tools MCP nuevas sobre el runner de la Fase 1 (instancia compartida ⇒ mutex por
  sesión efectivo): `get_interface_stats` (JSON canónico; `parsed:false` + crudo si
  el parser no reconoce; `persist` no-op hasta Fase 3), `get_link_status`
  (proyección liviana + `onlyProblems`), `get_bandwidth_utilization`
  (`device_rate` / `counter_delta` con descarte de deltas negativos por wrap).
- Vendor canónico de telemetría alineado con el `vendor_t` del esquema PostgreSQL
  de la Fase 3 (`CISCO_NXOS`, `ARUBA_AOSCX`, `FORTINET`, `MIKROTIK`, …).

## Unreleased — Telemetría Fase 1 (2026-06-10)

Tool MCP `run_readonly_command`: ejecución de comandos de solo lectura sin aprobación
humana, gobernada por whitelist regex por vendor (catálogo MCP: **25 tools**).

- Whitelist editable en `~/.opentermx/policies/readonly-commands.yaml` (default
  embebido; el server la relee con cache de 30 s). Whitelist pura: vendor no detectado
  o comando fuera de catálogo ⇒ rechazo. Deny-list para `show tech-support` y
  `diagnose sys kill`.
- `SessionCommandRunner`: detección de prompt por regex por vendor (override por
  sesión), des-paginación automática una vez por sesión, auto-respuesta de espacio a
  `--More--` y variantes, mutex por sesión para serializar comandos concurrentes,
  timeout (1–120 s, default 15) con output parcial y `timedOut: true`.
- `OutputCleaner`: strip de ANSI/VT100, backspaces, texto de paginador, eco del
  comando; CRLF→LF.
- Setup → AI Assistant → MCP: checkbox *Allow read-only commands without approval*
  (default ON; apagado vuelve al gate humano por comando) y botón *Editar whitelist…*.
- Auditoría en `audit-ia.csv` de toda invocación, incluidos los rechazos del validador.
- `propose_commands` intacta: gate humano obligatorio para todo lo mutativo.

## 1.1.0 — 2026-05-18

Phase 3 (Operación estructurada estilo clanet). Las 5 fases entregadas como milestone
único — agregan capas opt-in sobre la API MCP estable. **Wire format de las 10 tools
originales intocado**: todo lo nuevo es additive (params opcionales, header opcional
`X-OpenTermX-Role`, tools nuevas).

Catálogo MCP: **24 tools** (10 históricas + 14 nuevas).

### Fase 1 — Operation Context (`4477285`)
- 3 tools nuevas: `start_operation`, `end_operation`, `current_operation`.
- El cliente LLM declara una intención estructurada (`devices`, `forbidden_commands`,
  `success_criteria`, `constraints`). El server la inyecta como prefijo `[OPERATION
  CONTEXT …]` en cada `tools/call` exitoso y la usa como filtro pre-ejecución para
  comandos mutativos.
- Persistencia: `~/.opentermx/operations/<op-id>/context.json` + `closed.json` al cerrar.
  Recovery automático al arrancar.
- Deps nuevas: `jackson-dataformat-yaml`, `com.github.erosb:everit-json-schema:1.14.4`.
- Schema JSON Draft-07 + example en `examples/operation-context.example.yaml`.

### Fase 2 — Device Registry (`257ff1b`)
- 2 tools nuevas: `inventory_list` (filtros tags/groups/deviceType AND), `inventory_describe`.
- `SavedConnection` extendido con 4 campos opcionales (`alias`, `tags`, `groups`,
  `deviceType`) — back-compat 100%: Jackson respeta el JSON existente.
- `open_session` acepta `deviceAlias` opcional que resuelve protocol/host/port/username/
  credentialRef desde el registry. El modo `host+protocol` legacy sigue funcionando.
- UI Setup → Saved Connections gana 4 columnas inline (alias valida unicidad).
- **Invariante de seguridad**: `InventoryDevice` no tiene fields para credenciales — test
  estructural rompe si alguien intenta agregarlos.

### Fase 3 — Multi-agent roles + approval token HMAC (`b91ae1b`)
- Header HTTP opcional `X-OpenTermX-Role: OPERATOR|COMPLIANCE|VALIDATOR` (default OPERATOR).
- Whitelists hardcoded en `RoleAccessControl`. `tools/call` fuera del scope del rol →
  `-32601 Method not found` con mensaje claro.
- Tool nueva `compliance_evaluate` (solo COMPLIANCE) firma `approval_token` HMAC-SHA256.
- Secret en `~/.opentermx/mcp-secret.key` (32 bytes random, auto-gen, `0600` en POSIX).
- `propose_commands` gana params opcionales `approvalToken` + `deviceAlias`. Cuando la
  operation activa exige `require_compliance_approval: true`, sin token → rechazo.
- Auditoría: cada `compliance_evaluate` queda en `audit-ia.csv` con
  `sessionId="compliance:<opId>"` para cruce con `propose_commands`.

### Fase 4 — Snapshots pre/post + diff + rollback (`4a179b2`)
- 4 tools nuevas: `snapshot_create`, `snapshot_diff`, `snapshot_compare_to_criteria`,
  `rollback_propose`.
- Storage FS bajo `~/.opentermx/operations/<op-id>/snapshots/` (orphan path para ops null).
- Diff line-based; para `cisco_ios/hpe_comware/huawei_vrp` agrupa cambios por sección
  de config (header en col 0 + hijas indentadas).
- `SuccessCriteriaEvaluator`: 3 tipos (`command_output_contains` regex case-insensitive,
  `no_interface_down` regex multi-vendor, `route_exists` literal).
- `rollback_propose`: heurística "agregada → no <línea>, removida → reaplicar" para
  Cisco IOS y similares. NUNCA ejecuta — devuelve al loop operator/compliance.
- `propose_commands` exige snapshot previo si la op declara `require_snapshot: true`.

### Fase 5 — Policy engine determinístico (este release)
- **Módulo Gradle nuevo `:policy-engine`** — usable también por CLI futura, sin
  dependencia del transporte MCP.
- 4 tools nuevas: `policy_load` (path o YAML inline), `policy_list`, `policy_evaluate`
  (contra snapshot del device), `policy_audit` (sobre la flota).
- Reglas determinísticas: `pattern_deny`, `require`, `recommend`. Misma config + misma
  policy → mismo resultado bit-a-bit, sin LLM.
- Reporte JSON + Markdown copy-pasteable para tickets.
- Schema Draft-07 + ejemplo realista `policies/baseline-security-cisco-ios.example.yaml`
  con 6 reglas (no telnet, require SSH v2, no SNMP communities default, etc.).
- Hook `DeviceConfigParser` registrable por `device_type` — vacío en esta phase, pensado
  para parsers estructurados futuros sin tocar `RuleEvaluator`.
- Whitelist: COMPLIANCE puede `policy_load/list/evaluate` (insumo de sus decisiones).
  VALIDATOR adicionalmente puede `policy_audit` para auditorías masivas.

### Tests
- **24 unit test groups Kotlin** distribuidos entre `mcp-server` y `policy-engine`.
- **64 pytest passing** en `mcp-server/src/test/python/` (de 22 al cerrar 1.0.0).
- Build verde end-to-end con `./gradlew build` (66 tasks).

### Bump runtime
- `build.gradle.kts: version = "1.1.0"`. `BuildInfo.VERSION` propaga al `/mcp/health` y
  al payload de `initialize` (cubierto por `McpServerVersionTest`).

### Docs
- `docs/operation-context.md` (Fase 1)
- `docs/device-registry.md` (Fase 2)
- `docs/multi-agent.md` (Fase 3) con tradeoff single-LLM vs 3 procesos
- `docs/snapshots.md` (Fase 4)
- `docs/policy-engine.md` (Fase 5)

### Compatibilidad

- Clientes pre-Phase-3 funcionan idénticamente: nuevos headers/params son opcionales con
  defaults conservadores (sin op activa = sin inject, sin `X-OpenTermX-Role` = OPERATOR).
- `SavedConnection` JSON pre-Phase-3 deserializa con los 4 campos nuevos en null/empty.
- `mcp-secret.key` se autogenera al primer arranque del server post-upgrade.

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