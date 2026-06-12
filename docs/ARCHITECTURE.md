# Arquitectura de OpenTermX

OpenTermX es un emulador de terminal multiplataforma (Kotlin/Java, Gradle multi-módulo)
con un servidor MCP embebido que expone sesiones de red (SSH/Telnet/Serial) como tools
para clientes LLM. Este documento mapea los módulos y las invariantes de diseño.

## Invariantes (no negociables)

1. **Aprobación humana para cambios.** Todo comando que pueda modificar la configuración
   de un equipo pasa por el `ApprovalGate` (aprobación humana). Las tools mutativas
   (`propose_commands`, `run_macro`, `open_session`, `close_session`) llaman al gate; las
   de lectura (`run_readonly_command`, telemetría, fingerprint) usan la whitelist
   read-only estricta por vendor/perfil. Ningún camino ejecuta comandos del cliente con
   potencial mutativo sin pasar por el gate.
2. **Datos de equipos = no confiables.** Banners, descripciones de interfaz, hostnames
   LLDP/CDP y salida de comandos se tratan como datos, nunca como instrucciones: se
   redactan (credenciales), se sanitizan (RAG/catálogo/CSV) y se declaran en
   `untrustedFields` de las tools.
3. **Una sola vía de ejecución.** Sondas de fingerprint, scheduler de telemetría, snapshots
   y tools MCP usan el mismo `SessionCommandRunner` (mutex por sesión, des-paginación,
   timeout, limpieza). Cero copias de "esperar el prompt".
4. **El LLM nunca ve credenciales.** Se inyectan desde el keystore al usarlas; jamás en
   logs, argv ni respuestas de tools.

## Módulos

| Módulo | Responsabilidad | Depende de |
|--------|-----------------|-----------|
| `common` | `SessionRegistry`, abstracciones de conexión, `EventBus`, tipos de sesión. | — |
| `ssh-comm` / `telnet-comm` / `serial-comm` | Transportes concretos. SSH con verificación de host-key TOFU (`known_hosts`). | common |
| `net-parsers` | Parsing puro de output por vendor + sondas de fingerprint. Sin I/O ni transporte. | — |
| `ai-assistant` | Dominio IA: `RiskClassifier` (semáforo de riesgo), `VendorDetector`, `CredentialRedactor`, RAG (`KnowledgeBase` Lucene), `AiAuditLog`. Sin transporte MCP. | common |
| `policy-engine` | Compliance de configuración: evalúa reglas YAML (`pattern_deny`/`require`/`recommend`) contra snapshots. Determinístico, sin LLM. | — |
| `telemetry-db` | Persistencia PostgreSQL (HikariCP + Flyway): inventario, métricas, perfiles, catálogo. Degradación con gracia sin BD. | net-parsers |
| `integrations` | Conectores read-only a Zabbix / OpManager. | — |
| `macro-engine` | Macros Groovy. | common |
| `mcp-server` | **Transporte MCP**: Javalin HTTP/SSE, dispatcher JSON-RPC propio (`McpDispatcher`), handlers de tools, seguridad (`ApprovalGate` interfaz, tokens, roles, rate limit, validadores), `SessionCommandRunner`, orquestación de telemetría/fingerprint/onboarding/catálogo. | todos los anteriores |
| `rest-api` | API REST opcional (Javalin), simétrica al MCP. | common, ai-assistant |
| `app` | UI JavaFX: terminal, diálogos de Setup, managers (`McpServerManager`, `TelemetryDbManager`, `AutoFingerprintManager`) e impl JavaFX del `ApprovalGate`. | todos |
| `tools/mcp/` | Helper Python de **build-time** (`generate_docs.py`): genera la doc de las tools. NO runtime. | — |

## Flujo para el LLM (camino feliz)

`list_sessions` → ve marca/modelo/rol → `get_device_profile` +
`search_knowledge_base("gestión <modelo>")` → recibe el MD de gestión → diagnostica con
`run_readonly_command` / `get_interface_stats` → propone cambios con `propose_commands` →
**humano aprueba** → snapshot pre/post automático del cambio.

## Notas de seguridad relevantes

- **El rol MCP es auto-declarado por header.** `X-OpenTermX-Role` (default OPERATOR) lo
  fija el cliente. La separación COMPLIANCE/OPERATOR (multi-agente) es por lo tanto un
  control **advisorio**, no una frontera autenticada: un cliente puede declararse
  COMPLIANCE para emitir un approval token y usarlo como OPERATOR. Esto NO bypassa la
  invariante #1 — el `ApprovalGate` humano de `propose_commands` dispara siempre — pero la
  malla de roles no debe considerarse una barrera de seguridad sin atar el rol a una
  credencial/token. Ver `McpDispatcher.TransportContext.role`.
- **Bind seguro por default.** Sin token configurado, el servidor solo bindea a loopback;
  exponerlo a la red sin auth falla al arrancar.
- **Auditoría.** El registro durable es `command_audit` (PostgreSQL); el CSV
  (`AiAuditLog`) es best-effort con escritura serializada y defensa de inyección de
  fórmulas.
