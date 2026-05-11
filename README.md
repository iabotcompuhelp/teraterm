# OpenTermX

Emulador de terminal multi-protocolo estilo Tera Term, escrito en **Kotlin + Java + Groovy** sobre **JavaFX 21**, con un asistente de IA integrado para configuración de equipos de red.

> **Versión actual:** v4 — asistente IA con RAG, REST API embebida y comandos IA en el motor de macros.

---

## ✨ Lo nuevo en v4

| | |
|---|---|
| 🤖 **Asistente IA** | 5 providers (Claude · ChatGPT · Gemini · Ollama · LM Studio) con detección automática de vendor y contexto del terminal. |
| 📋 **RAG local** | Indexa tus políticas de infraestructura (.txt / .md / .pdf / .docx) con Apache Lucene; la IA respeta naming, VLANs e IP ranges. |
| 🚦 **Revisión obligatoria** | Cada bloque generado por IA pasa por un widget con semáforo SAFE / CONFIG / DANGEROUS y doble confirmación para comandos destructivos. |
| 🌐 **REST API** | Servidor Javalin embebido con 8 endpoints (`/api/sessions`, `/api/terminal/*`, `/api/macro/execute`, `/api/tftp/*`) y token Bearer. Integrable con n8n, Node-RED, SIEM. |
| 🧪 **Macros IA** | Nuevos comandos `ai_ask` y `ai_execute` en el motor Groovy — el macro pausa para aprobación humana antes de inyectar comandos. |
| 🔐 **Seguridad** | API keys cifradas en disco con AES-256-GCM; bind REST por defecto a `127.0.0.1`; auditoría CSV de cada decisión IA y cada petición REST. |

---

## Protocolos y módulos

| Módulo | Lenguaje | Responsabilidad |
|---|---|---|
| `:common` | Kotlin | Interfaces `Connection`, `Session`, `EventBus`, `SessionRegistry`, `LLMProvider`, `SecretCipher`. |
| `:serial-comm` | Java | RS-232 con jSerialComm (300–921600 baud, flow control, señales DTR/RTS/CTS/DSR/DCD/RI). |
| `:ssh-comm` | Java | SSH2 (jsch mwiede): contraseña, clave pública (RSA / ED25519 / ECDSA), agent, port forwarding, SFTP, TOFU. |
| `:telnet-comm` | Java | Telnet (Apache Commons Net) + TCP raw, opcional TLS-from-start. |
| `:file-transfer` | Java | XMODEM con CRC16. |
| `:tftp-service` | Java | TFTP cliente y servidor RFC 1350 + extensiones RFC 2347/2348/2349. |
| `:macro-engine` | Java + Groovy | Motor de macros estilo TTL con DSL Groovy. Comandos `sendln`/`waitfor`/`tftp_*`/**`ai_ask`/`ai_execute`**. |
| `:ai-assistant` | Kotlin | LLM providers, detector de vendor, clasificador de riesgo, parser de bloques, audit log, RAG con Lucene. |
| `:rest-api` | Kotlin | Servidor HTTP embebido (Javalin 6) con auth Bearer y log CSV opcional. |
| `:logger` | Kotlin | Logging de sesión (texto / HTML / raw), rotación, captura PNG. |
| `:app` | Kotlin + JavaFX | UI (ventana principal, diálogos, terminal renderer, chat IA, panel macros, TFTP). |

---

## Requisitos

- **JDK 21** (testado con Temurin 21.0.11)
- **JavaFX 21** (el plugin Gradle lo descarga automáticamente)
- **Windows / macOS / Linux** — el código no es plataforma-específico, pero la build del MSI requiere Windows + WiX 3.14

## Build

```powershell
# Windows con Temurin 21
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat build -x :app:packageMsi
```

```bash
# Linux / macOS
./gradlew build -x :app:packageMsi
```

**Tests:** 53 totales (incluye providers cloud + locales con MockWebServer, RAG con Lucene, REST live contra OkHttp, criptografía, parser).

## Empaquetado (Windows)

```powershell
.\gradlew.bat :app:packageMsi
# requiere WiX Toolset v3.14 en C:\Program Files (x86)\WiX Toolset v3.14
```

## Ejecutar

```powershell
.\gradlew.bat :app:run
```

---

## Quickstart

1. **Nueva conexión** — `Archivo → Nueva conexión…` (`Ctrl+N`). Elige TCP/IP (SSH/Telnet) o Serial.
2. **Configura la IA** — `Setup → AI Assistant…` (`Ctrl+Alt+A`):
   - Elige proveedor (Claude/OpenAI/Gemini/Ollama/LM Studio).
   - Para cloud: pega tu API Key y pulsa **Test connection**.
   - Para local: arranca Ollama / LM Studio en local y pulsa **🔄 Refrescar** para detectar modelos.
3. **(Opcional) Carga políticas** — en la pestaña **Knowledge Base** sube documentos `.md` / `.pdf` con tus convenciones de red.
4. **Abre el chat IA** — `Ctrl+I`. Pregunta en lenguaje natural; la IA recibe automáticamente las últimas 50 líneas del terminal, detecta el vendor y consulta el RAG.
5. **Revisa antes de ejecutar** — cualquier bloque de comandos generado aparece bajo la respuesta con semáforo por línea. Aprueba todos / seleccionados / edita / rechaza. Los **DANGEROUS** piden doble confirmación.

### Atajos clave

| Atajo | Acción |
|---|---|
| `Ctrl+N` | Nueva conexión |
| `Ctrl+I` | Mostrar/ocultar chat IA |
| `Ctrl+Alt+A` | Setup → AI Assistant |
| `Ctrl+Alt+R` | Setup → REST API |
| `Ctrl+M` | Editor de macros |
| `Ctrl+Shift+C / V` | Copiar / pegar terminal |
| `Ctrl+W` | Cerrar pestaña |

(Todos editables en `Setup → Shortcuts…`)

---

## REST API

Habilita el servidor en `Setup → REST API…`. Por defecto bindea `127.0.0.1:8080` con token Bearer obligatorio.

```bash
TOKEN="xK8a7-...tu-token..."
BASE="http://127.0.0.1:8080"

# Listar sesiones activas
curl -H "Authorization: Bearer $TOKEN" $BASE/api/sessions

# Inyectar texto en una sesión SSH/Telnet
curl -X POST -H "Authorization: Bearer $TOKEN" -H "content-type: application/json" \
  -d '{"sessionId":"<uuid>","text":"show ip int brief\n"}' \
  $BASE/api/terminal/send

# Leer las últimas N líneas del buffer
curl -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/terminal/buffer?sessionId=<uuid>&lines=20"

# Ejecutar un macro Groovy contra una sesión
curl -X POST -H "Authorization: Bearer $TOKEN" -H "content-type: application/json" \
  -d '{"sessionId":"<uuid>","script":"sendln \"show ver\"\nwaitfor \"#\"","timeoutSeconds":30}' \
  $BASE/api/macro/execute

# Arrancar / parar / estado del servidor TFTP embebido
curl -X POST -H "Authorization: Bearer $TOKEN" -H "content-type: application/json" \
  -d '{"port":6969,"rootDir":"C:/tmp","allowGet":true,"allowPut":false}' \
  $BASE/api/tftp/start
curl -X POST -H "Authorization: Bearer $TOKEN" $BASE/api/tftp/stop
curl -H "Authorization: Bearer $TOKEN" $BASE/api/tftp/status

# Health (sin auth)
curl $BASE/api/health
```

⚠️ Cambiar el bind a `0.0.0.0` expone la API a tu LAN. El diálogo lo advierte; configura firewall para restringir IPs origen.

---

## Macros con IA

El motor de macros (Groovy) expone dos nuevos comandos en v4:

```groovy
// Recolectar contexto
log "Comprobando el equipo..."
sendln "show version"
waitfor "#", 5

// Preguntar a la IA sin ejecutar nada
def vendor = ai_ask("¿qué vendor es este, basado en lo que ves?")
log "IA dice: $vendor"

// Pedir comandos y aprobarlos manualmente antes de inyectarlos
def result = ai_execute("configura un trunk en gi0/1 permitiendo VLAN 30 y 99")
log "Outcome: ${result.outcome()} · ejecutados=${result.executedCount()} fallidos=${result.failedCount()}"

if (result.outcome().name() == "APPROVED") {
    sendln "show interfaces gi0/1 trunk"
    waitfor "#", 5
}
```

**Comportamiento desde REST**: `ai_ask` funciona normalmente, pero `ai_execute` lanza `UnsupportedOperationException` — la spec no permite ejecutar comandos generados por IA sin operador presente.

---

## Persistencia (`~/.opentermx/`)

| Fichero / dir | Contenido |
|---|---|
| `settings.json` | Toda la configuración, incluyendo API keys cifradas (AES-256-GCM). |
| `knowledge/index/` | Índice Lucene del RAG. |
| `audit-ia.csv` | Auditoría de cada decisión IA: timestamp, sessionId, host, vendor, prompt, comandos, semáforo, contadores, output. |
| `api-log.csv` | Log opt-in de peticiones REST: timestamp, método, path, status, latencia, IP. |
| `known_hosts` | TOFU de claves SSH. |

---

## Seguridad y principios

1. **La IA nunca ejecuta directamente.** Todo comando generado pasa por el widget de revisión humana.
2. **Doble confirmación** para comandos destructivos (`erase`, `delete`, `reload`, `format`, `write erase`, `shutdown` aislado).
3. **API keys cifradas en reposo** con AES-256-GCM y master key derivada por PBKDF2 (no se requiere PIN del usuario, pero el fichero está cifrado contra acceso casual).
4. **REST API bindeada a localhost por defecto**; abrirla a la LAN requiere acción explícita.
5. **Auditoría completa**: cada interacción IA y cada petición REST queda en CSV append-only.
6. **SSH2 exclusivo**: SSH1 está desactivado por decisión de diseño (vulnerabilidades criptográficas conocidas).
7. **TOFU + known_hosts** para verificación de host SSH (sin opción de "always trust").

---

## Estructura del proyecto

```
OpenTermX/
├── build.gradle.kts              # config raíz (group, version, repos, JDK 21)
├── settings.gradle.kts           # módulos
├── gradle/libs.versions.toml     # versiones centralizadas
├── app/                          # UI principal (Kotlin + JavaFX)
├── common/                       # interfaces compartidas + crypto + AI types
├── serial-comm/  ssh-comm/  telnet-comm/  file-transfer/  tftp-service/
├── macro-engine/                 # Java + Groovy
├── logger/                       # logging y captura
├── ai-assistant/                 # providers, RAG, audit (Kotlin)
├── rest-api/                     # Javalin server (Kotlin)
└── .idea/teraterm v4.md          # spec maestra
```

---

## Roadmap completado en v4

- [x] **Fase 1** — fundación IA: módulo `:ai-assistant`, 3 providers cloud, AES-256, `SessionRegistry`, dialog Setup, status badge.
- [x] **Fase 2** — providers locales (Ollama, LM Studio) + chat panel lateral (Ctrl+I).
- [x] **Fase 3** — parser de bloques + widget de revisión + audit CSV + ejecución vía `CommandSink`.
- [x] **Fase 4** — RAG con Apache Lucene + loaders (.txt/.md/.pdf/.docx) + tab Knowledge Base.
- [x] **Fase 5** — módulo `:rest-api` con Javalin, 8 endpoints, token Bearer, lifecycle.
- [x] **Fase 6** — `ai_ask` / `ai_execute` en macros + bridges UI y headless + diálogo modal de aprobación.

---

## Créditos

- Inspirado por **Tera Term**.
- Stack: Kotlin 2.0, Java 21, Groovy 4, JavaFX 21, jsch (mwiede), jSerialComm, Apache Commons Net, Apache Lucene 9, Apache PDFBox 3, Javalin 6, OkHttp 4, Jackson, RichTextFX.
- Branding **COMPUHELP**.
