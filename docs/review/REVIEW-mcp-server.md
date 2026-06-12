# Auditoría de calidad y robustez de OpenTermX — 2026-06 (v2, por módulo)

> Sesión de **solo lectura** (no se modificó código; único archivo creado: este reporte).
> Encuadre: *verificar y endurecer* el código propio. Cinco ejes (correctitud, legibilidad,
> arquitectura, seguridad, performance). Severidades: **Critical / Requerido / Nit / Optional / FYI**.
> Garantía central verificada: *todo cambio de configuración en un equipo pasa por aprobación
> humana (ApprovalGate)*.

---

## Resumen ejecutivo

**Veredicto global: APPROVE con cambios requeridos.**

La garantía central **se cumple en todo el código**: se enumeraron las 9 tools `mutating=true`
y cada una pasa por aprobación humana o por la whitelist read-only estricta (tabla en
§mcp-server). No hay ruta de ejecución que la eluda → **sin hallazgos Critical**.

Pendientes para "aprobado por staff engineer": **integridad de la auditoría** (CSV sin
concurrencia segura ni defensa de inyección de fórmulas, con datos de equipos no confiables),
**malla de roles auto-declarada por header**, **semáforo SAFE engañoso** para verbos no
catalogados, una **dependencia muerta** (`mcp-kotlin-sdk`), e **higiene de repo** (scratch
files, sin LICENSE, sin CI). Ninguno rompe la garantía central.

---

## Paso 0 — Evidencia (build + tests)

`./gradlew check` (incluye unit de todos los módulos + pytest del mcp-server), JDK Temurin 21:

```
BUILD SUCCESSFUL in 48s · 79 actionable tasks
pytest (mcp-server): 73 passed in 45.19s
```

Compila y **todo verde**. Base revisable. Tests con PostgreSQL real embebido (zonky),
fixture-driven (net-parsers) y black-box HTTP/SSE (pytest).

---

## mcp-server

### Garantía de aprobación humana — cobertura explícita

Las 9 tools `mutating=true` (`ToolDefinitions.kt`) y su control de ejecución:

| Tool | Control | ¿Comando del cliente? |
|------|---------|-----------------------|
| `propose_commands` | **ApprovalGate humano** (`ProposeCommandsHandler` siempre llama `reviewCommands`, incluso con token de compliance) | Sí → gate |
| `run_macro` | **ApprovalGate humano** (`RunMacroHandler`) | Macro definida por operador → gate |
| `open_session` | **ApprovalGate humano** (`OpenSessionHandler` + `SessionOpener`) | — |
| `close_session` | **ApprovalGate humano** (`CloseSessionHandler:37` — cerrar puede interrumpir trabajo) | — |
| `run_readonly_command` | **Whitelist read-only estricta** por vendor/perfil (`ReadOnlyCommandValidator`, 6 capas fail-closed) | Sí → whitelist, sin gate (read-only por construcción) |
| `refresh_device_fingerprint` | Whitelist read-only: cada sonda se valida antes de enviarse; `blocked`=no se ejecuta (`FingerprintService`) | Comandos **internos** del catálogo, no del cliente |
| `get_interface_stats` / `get_link_status` / `get_bandwidth_utilization` | Comandos **internos** read-only (`ParserRegistry`) vía `SessionCommandRunner` | No (catálogo interno) |

**Conclusión: la garantía se sostiene.** El único camino que inyecta comandos *del cliente*
con potencial mutativo es `propose_commands`/`run_macro`, ambos detrás del gate humano.

### Hallazgos mcp-server

| # | Sev | Ubicación | Hallazgo | Corrección |
|---|-----|-----------|----------|-----------|
| M-1 | **Requerido** | `ai-assistant/.../audit/AiAuditLog.kt:55-80` | **Inyección de fórmulas CSV.** `csvEscape` neutraliza `,"\n\r` pero no el prefijo `= + - @`. `outputTail`/`prompt`/`host` derivan de **output de equipos (no confiable)**: un banner `=HYPERLINK("http://evil")` se ejecuta al abrir el CSV en Excel/LibreOffice. | Prefijar con `'` los campos cuyo primer char ∈ `= + - @`; test con banner malicioso. |
| M-2 | **Requerido** | `ai-assistant/.../audit/AiAuditLog.kt:36-53` | **Append sin lock.** Con 2 clientes MCP concurrentes (`propose_commands` simultáneos) las dos llamadas corren la carrera de `Files.exists`/header + `writeString(APPEND)` de filas multi-write → **headers duplicados y filas corruptas**. El audit es la verification story del producto. | Serializar `append()` (lock de instancia o `FileChannel.lock()`), o declarar el CSV best-effort y hacer de `command_audit` (PostgreSQL) el registro de verdad. |
| M-3 | **Requerido** | `mcp-server/.../protocol/McpDispatcher.kt:151,348` | **Rol auto-declarado por header `X-OpenTermX-Role`** (default OPERATOR). La malla COMPLIANCE/OPERATOR es advisoria: un cliente puede declararse COMPLIANCE, emitir un approval token, y usarlo como OPERATOR. *No bypassa la garantía* (el gate humano de `propose_commands` igual dispara), pero el control de compliance no es una frontera real. | Documentar la limitación, o atar el rol al token/credencial. |
| M-4 | **FYI/diseño OK** | `mcp-server/.../McpServer.kt` (`enforceAuth` en `app.before`; `start()` rechaza bind no-loopback sin auth) | **Auth correcta y default seguro:** el filtro `before` cubre `/mcp`, `/mcp/sse` y `/mcp/health`; sin token el server **solo** bindea a `127.0.0.1` (si no, `IllegalStateException`→FAILED). TLS opcional sin downgrade. | — (mantener). |
| M-5 | **Refutado** | `InspectSessionHandler.kt:28-41` | `inspect_session` está **acotado server-side a 500 líneas** (`Args.optionalInt(min,max)`) **y redactado** (`CredentialRedactor`). | Opcional: bajar el *default* (no el max). |
| M-6 | **OK** | `McpDispatcher.kt:142-220` | Validación de entrada robusta: método desconocido→`METHOD_NOT_FOUND`, params malformados→`INVALID_PARAMS`, excepciones atrapadas→`toolCallError`/`INTERNAL_ERROR`. Negociación de versión MCP correcta. | — |

---

## policy-engine y policies/

Módulo de **compliance de configuración** (FAIL/WARN/PASS de snapshots contra reglas YAML),
distinto del semáforo de riesgo de comandos (ese vive en `ai-assistant.RiskClassifier`).

- **Determinístico y data-driven** (`RuleEvaluator.kt`): tipos `pattern_deny` / `require` /
  `recommend`. Agregar una **regla** es YAML puro; agregar un **tipo nuevo** toca un único
  `when` central acotado (línea 43-52). Regex inválida → WARN (no crashea); tipo desconocido →
  WARN. Robusto y legible. **Sin hallazgos.**
- `policies/baseline-security-cisco-ios.example.yaml` es un ejemplo, no config de runtime.

### Riesgo de comandos (ai-assistant.RiskClassifier) — relacionado con "no marcar mutativo como read-only"

| # | Sev | Ubicación | Hallazgo | Corrección |
|---|-----|-----------|----------|-----------|
| P-1 | **Requerido** | `ai-assistant/.../safety/RiskClassifier.kt:142-144` | **Fallback SAFE para verbos no catalogados.** Un comando no indentado cuyo primer token no está en ningún set cae a **SAFE**. `debug all`, `clear ip bgp *`, `test`, Junos `request system ...` muestran semáforo **verde** → invitan al rubber-stamp. *La garantía NO se rompe* (gate humano + whitelist read-only independiente), pero el semáforo engaña al operador. | Fallback no-indentado → **CONFIG**; manejar `debug`/`clear`/`test`/`request`. Test por comando. |
| P-2 | **FYI** | `RiskClassifier.kt:79-118` | **Positivo:** el escalado anti-inyección (control chars, command substitution, pipes de exfiltración, redirección shell, encadenadores split-and-worst, multilínea) es exhaustivo y bien testeado. | — (mantener). |

---

## Comunicación (ssh-comm, serial-comm, telnet-comm)

- **Host-key checking correcto (positivo).** `SshConnection.java` usa TOFU +
  `~/.ssh/known_hosts` con default `RejectAllHostKeyVerifier` (rechaza claves desconocidas
  salvo verifier explícito). Refuta la preocupación de "host key en SSH".
- **Sin fuga de credenciales en logs.** Grep de `log.*(password|passphrase|secret|credential)`
  en los tres módulos: vacío. Las credenciales se enrutan por `UserInfo`/keystore, no se loguean.
- **Cierre de recursos limpio (positivo).** `disconnect()` es idempotente (CAS de estado);
  `teardown()` hace cleanup→interrupt→`join(2_000)` con warning si el reader no termina —
  terminación observable, sin hilos colgados ni canales abiertos (`SshConnection.java:326-361`).
- **Datos del equipo como no confiables:** el output pasa por `CredentialRedactor` antes de
  cruzar a clientes MCP y por `OutputCleaner`/sanitización en RAG y catálogo. Criterio aplicado.

**Sin hallazgos Requerido en comunicación.**

---

## Arquitectura (ai-assistant, tools/mcp, límites de módulo)

Responsabilidades **bien separadas, sin solapamiento de runtime ni código muerto entre
módulos**:

| Módulo | Responsabilidad |
|--------|-----------------|
| `ai-assistant` | Dominio IA puro: `RiskClassifier`, `VendorDetector`, `CredentialRedactor`, RAG (`KnowledgeBase` Lucene), `AiAuditLog`. **Sin transporte.** |
| `mcp-server` | Transporte MCP (Javalin HTTP/SSE), dispatcher JSON-RPC, handlers de tools, seguridad (ApprovalGate iface, tokens, roles, rate limit, validadores), `SessionCommandRunner`, orquestación telemetría/fingerprint/onboarding/catálogo. |
| `policy-engine` | Compliance de config (reglas vs snapshots). Independiente. |
| `net-parsers` | Parsing puro + sondas de fingerprint. Sin I/O. |
| `telemetry-db` | Persistencia PostgreSQL (Hikari/Flyway). |
| `common` | `SessionRegistry`, abstracciones de conexión, EventBus. |
| `tools/mcp/` | **Helper Python de build-time** (`generate_docs.py`) — genera docs de las tools. NO runtime, no duplica nada del mcp-server. |

| # | Sev | Hallazgo | Corrección |
|---|-----|----------|-----------|
| A-1 | **Optional** | No hay `ARCHITECTURE.md` que documente esta separación (la `family` del catálogo decide perfiles, el ApprovalGate es interfaz en mcp-server con impl JavaFX en app, etc.). | Agregar `docs/ARCHITECTURE.md` corto con el mapa de módulos + las invariantes (gate, datos no confiables, una vía de ejecución). |

---

## Dependencias (mantenimiento preventivo)

Análisis del catálogo `gradle/libs.versions.toml` cruzado con uso real en el código (cada
dependencia es un pasivo):

| # | Sev | Dependencia | Hallazgo | Corrección |
|---|-----|-------------|----------|-----------|
| D-1 | **Requerido** | `io.modelcontextprotocol:kotlin-sdk:0.5.0` (`mcp-server/build.gradle.kts:68`) | **Dependencia muerta.** Declarada como `implementation` pero **sin un solo import** en el código (`grep io.modelcontextprotocol` → vacío): el dispatcher MCP es propio (`McpDispatcher`). Arrastra un jar + transitivos a runtime/installers sin uso. | `git rm` la línea del build + la entrada del catálogo. Verificar `./gradlew check` verde. |
| D-2 | **FYI** | `com.github.mwiede:jsch:0.2.21` | **Correcto:** fork mantenido de JSch (host-key capable), no el JCraft abandonado. Versión razonablemente actual. | — (seguir el fork). |
| D-3 | **FYI** | Jackson 2.18.1, Lucene 9.12.0, Javalin 6.4.0, Flyway 10.20.1, postgresql 42.7.4, HikariCP 5.1.0, okhttp 4.12.0, groovy 4.0.24 | Todas razonablemente actuales; sin CVE evidente; todas con mantenimiento activo. `everit-json-schema` se usa (OperationContextLoader, PolicyLoader) — viva. | — |
| D-4 | **Optional** | — | Sin escaneo automatizado de dependencias. | Agregar OWASP dependency-check o el plugin Gradle `versions` al CI. |

---

## Higiene del repositorio

| # | Sev | Hallazgo | Corrección |
|---|-----|----------|-----------|
| H-1 | **Requerido** | **5 scratch files versionados** en la raíz: `.claude-mcp-progress*.json` (×4) + `.claude-render-fix-progress.json`. | `git rm` + patrón en `.gitignore`. |
| H-2 | **Requerido** | **Sin LICENSE** → "todos los derechos reservados" por defecto. | Crear `LICENSE` (preguntar al autor la licencia — mensaje 7). |
| H-3 | **Requerido** | **Sin CI** (`.github/workflows` ausente): nada impide mergear un build roto. | `ci.yml`: `./gradlew check` (incluye pytest) en push y PR. |
| H-4 | **Nit** | README raíz es un hub de 8 líneas sin quickstart de build. | Agregar sección "Build & test" (`./gradlew check`, JDK 21). |
| H-5 | **Refutado** | `.idea/` **no** versiona config de IDE: `.gitignore` excluye `.idea/*` y re-incluye solo 2 specs intencionales (`teraterm.md`, `opentermx-mcp.md`). | — |

---

## Checklist code-review-and-quality

- [x] **Correctitud** — build + 73 pytest + unit verdes; dispatcher robusto ante input malformado.
- [x] **Legibilidad** — KDoc denso, "por qué" documentado, nombres claros. Por encima de la media.
- [x] **Arquitectura** — módulos con límites claros, sin solapamiento; garantía central centralizada y reusada. Falta `ARCHITECTURE.md` (A-1).
- [~] **Seguridad** — garantía central intacta; pendiente integridad del audit (M-1/M-2), rol por header (M-3), semáforo SAFE (P-1). Host-key TOFU y redacción correctos.
- [x] **Performance** — caché TTL de perfiles, indexación Lucene incremental por hash, particiones mensuales, mutex por sesión, I/O de BD fuera del hilo FX.

---

## Plan de corrección (commits atómicos ~100–300 líneas; refactors separados de fixes)

**Fixes funcionales (con test de regresión):**

1. `fix(audit): neutralizar inyección de fórmulas CSV` — M-1. `csvEscape` antepone `'` a campos con primer char `= + - @`. Test: banner `=HYPERLINK(...)` queda inerte. (~40 líneas)
2. `fix(audit): serializar el append del audit CSV` — M-2. Lock de instancia + header atómico. Test concurrente: N hilos → N filas + 1 header. (~60 líneas)
3. `fix(safety): verbos no catalogados no son SAFE` — P-1. Fallback no-indentado → CONFIG; `debug`/`clear`/`test`/`request`. Test por comando. (~80 líneas)

**Mantenimiento / endurecimiento:**

4. `chore(deps): eliminar dependencia muerta mcp-kotlin-sdk` — D-1. Quitar del build + catálogo; `./gradlew check` verde. (~10 líneas)
5. `docs(security): documentar que el rol MCP es auto-declarado` — M-3. KDoc dispatcher + README módulo. (~30 líneas)
6. `docs(arch): agregar ARCHITECTURE.md` — A-1. (~60 líneas)

**Higiene (sin test; verificar `./gradlew check` verde):**

7. `chore: eliminar scratch files de progreso` — H-1. (~10 líneas)
8. `chore: agregar LICENSE` — H-2. **Preguntar la licencia antes de crear.**
9. `ci: GitHub Actions con ./gradlew check` — H-3. (~40 líneas)
10. `docs: quickstart de build en el README raíz` — H-4. (~20 líneas)

**Backlog opcional:** M-5 (default de `inspect_session`), D-4 (dependency scanning en CI).

---

### Archivos leídos (trazabilidad)

`mcp-server`: McpDispatcher.kt, McpServer.kt, ProposeCommandsHandler.kt, CloseSessionHandler.kt,
InspectSessionHandler.kt, ReadOnlyCommandValidator.kt, ToolDefinitions.kt, build.gradle.kts.
`ai-assistant`: AiAuditLog.kt, RiskClassifier.kt, VendorDetector.kt.
`policy-engine`: RuleEvaluator.kt (+ inventario del módulo).
`ssh-comm`: SshConnection.java. Infra: `.gitignore`, `libs.versions.toml`, `README.md`,
`settings.gradle.kts`, salida de `./gradlew check`, `git ls-files`, grep de uso de
dependencias (mcp-kotlin-sdk, everit-json-schema) y de logging de credenciales.
```
