# Implementar módulo `mcp-server` en OpenTermX

Vas a agregar un módulo nuevo que exponga las capacidades de OpenTermX (sesiones SSH/Telnet/Serial, knowledge base, macros) como **tools del protocolo MCP**, para que clientes externos —Claude Desktop, Cursor, Claude Code, Continue.dev, Cline— puedan operar OpenTermX usando su propio LLM. OpenTermX NO consume LLMs en este módulo: solo expone tools y responde a llamadas.

---

## Protocolo de trabajo (leer primero)

**Modo autónomo.** Ejecutá la lista de tareas de abajo en orden, sin pedir confirmación entre pasos. No me preguntes "¿continúo con el paso N?" — solo continuá. Para cada tarea, antes de empezar avisame en UNA línea qué vas a hacer; al terminar, otra línea con qué quedó hecho. NO me muestres el código completo que escribiste, excepto cuando sea un patrón nuevo no obvio (máximo 15 líneas en ese caso).

**Interrupción.** Si escribo `stop` en cualquier momento:
1. Terminá la tarea atómica que esté en curso (no la abandones a medias dejando el build roto).
2. Guardá el estado en `.claude-mcp-progress.json` en la raíz del repo con este shape:
   ```json
   {
     "lastUpdate": "ISO-8601",
     "currentTask": 4,
     "tasks": {"1": "done", "2": "done", "3": "done", "4": "in-progress", "5": "pending", ...},
     "filesCreated": ["mcp-server/build.gradle.kts", "..."],
     "filesModified": ["settings.gradle.kts", "..."],
     "lastBuildStatus": "passing|failing|unknown",
     "openIssues": ["descripción corta de cualquier cosa pendiente"]
   }
   ```
3. Reportame en una respuesta corta: qué tarea quedó en curso, qué tests pasan, qué falta.

**Resume.** Si escribo `resume` o `continue`:
1. Leé `.claude-mcp-progress.json`. Si no existe, asumí inicio desde cero.
2. Retomá exactamente donde quedaste. NO repitas tareas marcadas `done` salvo que `lastBuildStatus` sea `failing`, en cuyo caso primero arreglá el build.

**Errores.** Si una tarea falla (compilación rota, test rojo, dependencia no encontrada), NO avances a la siguiente. Arreglá esa primero. Si después de dos intentos no podés, pausá como si yo hubiera escrito `stop` y explicame el error en términos concretos (qué archivo, qué línea, qué falló).

---

## Contexto del proyecto

OpenTermX es un emulador de terminal multiplataforma tipo Tera Term, Kotlin + JavaFX, Gradle Kotlin DSL, JVM 21. Módulos existentes: `app`, `ai-assistant`, `common`, `ssh-comm`, `telnet-comm`, `serial-comm`, `file-transfer`, `macro-engine`, `logger`, `tftp-service`, `rest-api`, `native`. Convenciones: paquetes bajo `com.opentermx.*`, comentarios y docstrings en español, tests con JUnit 5 (Jupiter).

Piezas que vas a reusar (no las modifiques, solo invocalas):

- `common`: `SessionRegistry` (registro de sesiones activas con buffer y metadata), `SessionId`, `ProviderKind`.
- `ai-assistant`: `RiskClassifier` (SAFE/CONFIG/DANGEROUS por vendor), `CodeBlockParser`, `KnowledgeBase` (RAG sobre Lucene), `VendorDetector`, `AiAuditLog`.
- `macro-engine`: contrato `MacroAiBridge` con separación `ask` (read-only) / `execute` (mutativa, requiere operador).
- `app/ui/ai`: `AiExecuteApprovalDialog` (panel de revisión con semáforo de riesgo).

---

## Decisiones arquitectónicas (cerradas — no las re-debatas)

- **Módulo nuevo:** `mcp-server`, paralelo a `rest-api`.
- **SDK:** `io.modelcontextprotocol:kotlin-sdk` (última versión estable en Maven Central; buscala vía `gradle dependencies` o consultando el repo oficial. Si no la encontrás, pausá y avisame).
- **Transporte:** HTTP/SSE en `127.0.0.1:8765` por default. Puerto y bind address configurables. NUNCA bind por default a `0.0.0.0`.
- **Ciclo de vida:** arranca en `MainWindow` cuando la app sube, se apaga en el shutdown hook.
- **Autenticación:** token bearer opcional (si está seteado en settings, las requests deben traerlo en `Authorization: Bearer <token>`).
- **Invariante de seguridad:** tools mutativas pasan SIEMPRE por `AiExecuteApprovalDialog` antes de inyectar comandos. Esta regla NO se negocia, incluso si parece "demás" para un comando trivial.

---

## Stack por componente (qué lenguaje usar y por qué)

El repo es polyglot (Kotlin, Java, Groovy, C++ vía JNI en `native`, y ahora sumamos Python). La elección de lenguaje por capa no es preferencia, es lo que cada pieza requiere:

- **Núcleo del servidor MCP → Kotlin (no negociable).** El servidor necesita acceso EN PROCESO a `SessionRegistry` (objetos JVM vivos con sockets NIO, threads, buffers), `KnowledgeBase` (índice Lucene en memoria), y al `AiExecuteApprovalDialog` (que vive en el JavaFX Application Thread). Mover esto fuera del JVM implicaría IPC, serialización y pérdida del contexto vivo. Kotlin idiomático, `kotlinx.coroutines` para concurrencia, interop transparente con Java cuando aporte.

- **Tests unitarios → Kotlin + JUnit 5.** Mismo módulo, mocks de servicios. Rápidos, corren en `./gradlew check` estándar.

- **Tests de integración end-to-end → Python + pytest + httpx.** Acá Python gana fuerte: black-box, arrancás el servidor real, le pegás HTTP/SSE con requests reales, validás contra los schemas declarados. `pytest` + `httpx` es más legible y con menos boilerplate que OkHttp + JUnit para este tipo de test. Viven en `mcp-server/src/test/python/`, con `pyproject.toml` propio.

- **Tooling y scripts de soporte → Python.** Generación de docs a partir de schemas, smoke tests post-deploy, scripts de verificación entre versiones. Viven en `tools/mcp/`.

- **Java → solo si conviene puntualmente.** Si un archivo encaja mejor en Java por interop con una lib específica del SDK MCP o por consistencia con código adyacente (los módulos `macro-engine` y `logger` ya mezclan Java + Kotlin sin problema), está permitido. Pero Kotlin es el default para todo lo nuevo en este módulo.

- **C++ → no aplica acá.** El módulo `native` existente (serial port via JNI) es el único caso legítimo de C++ en el proyecto. Un servidor MCP es I/O-bound y vive sobre el JVM; C++ no aporta nada y agregaría una superficie nativa innecesaria. NO crear módulos en C++ para esta tarea.

---

## Lista de tareas (orden estricto)

### Tarea 1 — Esqueleto del módulo
- Crear `mcp-server/build.gradle.kts` con plugin `kotlin.jvm`, toolchain 21, dependencias `api(project(":common"))`, `implementation(project(":ai-assistant"))`, `implementation(project(":macro-engine"))`, SDK MCP, `kotlinx.coroutines`, `slf4j`, `jackson`.
- Crear `mcp-server/src/main/kotlin/com/opentermx/mcp/` con package vacío.
- Agregar `include(":mcp-server")` en `settings.gradle.kts`.
- Agregar el alias del SDK MCP en `gradle/libs.versions.toml`.
- Verificar `./gradlew :mcp-server:build` compila vacío.

### Tarea 2 — Modelos de tools
Crear `mcp-server/src/main/kotlin/com/opentermx/mcp/tools/ToolDefinitions.kt` con las 4 tools, cada una con `name`, `description` (español), JSON Schema de input y output:

1. **`list_sessions`** — read-only. Input: `{}`. Output: `{sessions: [{sessionId, protocol, host, port?, username?, vendor}]}`.
2. **`inspect_session`** — read-only. Input: `{sessionId: string, lastLines?: int (default 50, max 500)}`. Output: `{sessionId, protocol, host, port?, username?, vendor, lines: string[]}`.
3. **`search_knowledge_base`** — read-only. Input: `{query: string, topK?: int (default 5, max 20)}`. Output: `{hits: [{source: string, chunkIndex: int, text: string, score: float}]}`.
4. **`propose_commands`** — mutativa. Input: `{sessionId: string, commands: string[], rationale?: string}`. Output: `{approved: bool, executed: int, rejected: int, auditLogId: string, output?: string, riskSummary: {safe: int, config: int, dangerous: int}}`.

### Tarea 3 — Handlers
Crear un handler por tool en `mcp-server/src/main/kotlin/com/opentermx/mcp/handlers/`. Las read-only resuelven sincrónicas leyendo de `SessionRegistry` / `KnowledgeBase`. Para `propose_commands`:
- Clasificar cada comando con `RiskClassifier` (detectar vendor desde el buffer de la sesión vía `VendorDetector`).
- Construir un `CompletableDeferred<ApprovalResult>` y lanzar `AiExecuteApprovalDialog` con `Platform.runLater {}`.
- Esperar el deferred sin bloquear el hilo del servidor (`withContext(Dispatchers.IO)` para la espera).
- Si se aprueba, inyectar línea por línea por la conexión activa (vía `SessionRegistry.connectionOf(sessionId).sendLine(...)`).
- Loguear todo en `AiAuditLog`.

### Tarea 4 — Bootstrap del servidor
Crear `mcp-server/src/main/kotlin/com/opentermx/mcp/McpServer.kt`:
- `start(port: Int, bindAddress: String, token: String?)`: arma el `Server` del SDK MCP, registra las 4 tools, lo monta sobre HTTP/SSE con el transporte que el SDK provea (Ktor si aplica). Middleware de auth que valida el bearer token si está seteado.
- `stop()`: para el servidor con timeout de 5s, fuerza si no responde.
- Estado interno: `enum class Status { STOPPED, STARTING, RUNNING, STOPPING, FAILED }` con un `StateFlow` para que la UI lo observe.

### Tarea 5 — Settings
Extender `app/src/main/kotlin/com/opentermx/app/settings/AiAssistantSettings.kt` con:
- `mcpServerEnabled: Boolean = false` (default OFF por seguridad).
- `mcpServerPort: Int = 8765`.
- `mcpServerBindAddress: String = "127.0.0.1"`.
- `mcpServerToken: EncryptedValue?` (cifrar con `SecretCipher` igual que las API keys actuales).

Asegurate de que el serializer JSON existente persista los nuevos campos.

### Tarea 6 — Integración con MainWindow
En `app/src/main/kotlin/com/opentermx/app/ui/MainWindow.kt`:
- Después de inicializar `SessionRegistry` y `KnowledgeBaseHolder`, si `settings.mcpServerEnabled`, llamar a `McpServer.start(...)`.
- Registrar shutdown hook que llama a `McpServer.stop()`.
- Bind del `Status` del servidor a la status bar: `"MCP: listening on 127.0.0.1:8765"` / `"MCP: off"` / `"MCP: failed (click for details)"`.

### Tarea 7 — UI de Setup
En el diálogo `Setup → AI Assistant`, agregar nueva sección/pestaña **"MCP Server"** con:
- Checkbox `Enabled`.
- Spinner `Port` (1024-65535).
- TextField `Bind address` con validación (default `127.0.0.1`, warning visible si el usuario pone `0.0.0.0`).
- PasswordField `Token` con botón "Generate" (UUID v4) y botón "Show/Hide".
- Label informativo con el snippet de `claude_desktop_config.json` listo para copiar.
- Al guardar: si el servidor está corriendo y cambió port/bind/token, reiniciarlo.

### Tarea 8 — Tests (dos capas, dos lenguajes)

**8a. Unit tests — Kotlin + JUnit 5** en `mcp-server/src/test/kotlin/`:
- Un test por handler con mocks de `SessionRegistry` / `KnowledgeBase` / `RiskClassifier` / `AiExecuteApprovalDialog`.
- Casos: read-only OK, `sessionId` inexistente, KB vacía, RiskClassifier devolviendo DANGEROUS, approval dialog rechazando.
- Que corran con `./gradlew :mcp-server:test`.

**8b. Integration tests — Python + pytest** en `mcp-server/src/test/python/`:
- Crear `pyproject.toml` con `pytest`, `httpx`, `pytest-asyncio`, y el SDK MCP de Python si está disponible en PyPI (sino HTTP/SSE directo).
- Crear un fixture `server` que arranque `McpServer` real en un puerto efímero (vía `./gradlew :mcp-server:bootTestServer` o spawn directo del JAR) contra un `SessionRegistry` poblado con sesiones mock.
- Tests black-box:
  - Cada tool con request HTTP real, validación del shape de response contra el schema declarado (usar `jsonschema` para validar).
  - Auth: sin token cuando se requiere → 401. Token inválido → 401. Token válido → 200.
  - Error paths: `sessionId` malformado → 400 con mensaje claro. KB sin índice → respuesta vacía, no crash.
  - `propose_commands` con approval dialog auto-aprobando (variable de entorno `OPENTERMX_TEST_AUTO_APPROVE=1` que tu código de test reconozca y bypassée el diálogo) y rechazando.
- Crear Gradle task `:mcp-server:pythonTests` que:
  1. Crea venv en `mcp-server/build/python-venv/` si no existe.
  2. Instala dependencias de `pyproject.toml`.
  3. Ejecuta `pytest mcp-server/src/test/python/ -v`.
  4. Reporta exit code al build de Gradle.
- Linkear `pythonTests` a la task `check`, de modo que `./gradlew :mcp-server:check` corra unit + integration.

### Tarea 9 — Documentación
- Crear `mcp-server/README.md` (español) con: propósito del módulo, cómo habilitarlo desde Setup, snippets de configuración para Claude Desktop, Cursor y Claude Code (los tres más relevantes), descripción de cada tool con ejemplo de input/output, notas de seguridad (por qué default bind es localhost, qué hace el token).
- Actualizar `.idea/teraterm.md` agregando el módulo `mcp-server` a la lista de módulos.

### Tarea 10 — Cierre
- `./gradlew build` completo, incluyendo `:mcp-server:pythonTests`.
- Reportar: total de archivos creados, archivos modificados, tests agregados (unit + python), líneas netas. Marcar todas las tareas como `done` en `.claude-mcp-progress.json`.

### Tarea 11 — Tooling Python en `tools/mcp/`
Crear scripts cortos en Python, cada uno autocontenido y ejecutable:
- `tools/mcp/pyproject.toml` con dependencias compartidas (`httpx`, `pydantic`, `jinja2`, `click`).
- `tools/mcp/smoke_test.py`: CLI que apunta a un OpenTermX corriendo (`--url`, `--token`), ejecuta una llamada a cada tool, valida tiempos de respuesta < 2s, exit code != 0 si algo falla. Útil post-deploy.
- `tools/mcp/generate_docs.py`: introspección de los schemas declarados (vía endpoint `list_tools` del servidor MCP corriendo, o parsing estático del JSON exportado), genera la sección "Tools" del `mcp-server/README.md` usando una plantilla Jinja2. Idempotente: corriendo dos veces da el mismo resultado.
- `tools/mcp/schema_diff.py`: compara dos snapshots de schemas (entre branches o versiones de release) y reporta breaking changes (campos eliminados, tipos cambiados, requires agregados). Imprime markdown listo para el changelog.
- README corto en `tools/mcp/README.md` explicando para qué sirve cada script y cómo invocarlos.

---

## Lo que NO toques

- **No modifiques** los providers existentes (`ClaudeProvider`, `OpenAIProvider`, `GeminiProvider`, `OllamaProvider`, `LMStudioProvider`). Quedan exactamente como están.
- **No remuevas** `AiChatPanel`, `HeadlessMacroAiBridge`, ni `AiInvoker`. Coexisten con el servidor MCP.
- **No agregues** llamadas salientes a APIs de LLMs desde `mcp-server`. El servidor MCP NO consume LLMs, solo expone tools.
- **No cambies** las firmas públicas de `LLMProvider`, `MacroAiBridge` ni `LlmRequest`/`LlmResponse`.
- **No bindeés** el servidor a `0.0.0.0` por default. Solo `127.0.0.1`.
- **No reescribas** el núcleo del servidor en otro lenguaje (Python, C++, Go). La elección de Kotlin para `mcp-server/src/main/` es restricción técnica, no opinión. Python solo en `src/test/python/` y `tools/mcp/`. C++ no se toca en esta tarea.

---

Empezá por **Tarea 1**. Adelante.
