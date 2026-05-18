# OpenTermX MCP — guía de usuario

> **Una página**: OpenTermX puede actuar como servidor MCP. Esto significa que tu cliente
> de IA favorito (Claude Desktop, Cursor, Cline, Continue) puede listar las sesiones SSH/Telnet/Serial
> que tenés abiertas, consultar tu base de conocimiento RAG, y proponer comandos para inyectar
> en un dispositivo de red — pero **siempre tenés que aprobarlos vos** antes de que se ejecute nada.
> La IA es el que sugiere; vos sos el que decide.

## Conectar Claude Desktop en 5 minutos

1. Abrí OpenTermX.
2. `Setup → AI Assistant…`, pestaña **MCP Server**.
3. Marcá *Enable MCP server*. Apretá *Generate* para crear un token.
4. En el dropdown del snippet, elegí **CLAUDE_DESKTOP**. Click en *Copy snippet*.
5. Click en *Open folder* — se abre la carpeta del `claude_desktop_config.json`.
6. Editá ese archivo y pegá el snippet (mergea con lo que ya haya en `mcpServers`).
   ![screenshot: claude_desktop_config_paste]
7. Cerrá y reabrí Claude Desktop. En una conversación nueva, vas a ver el icono 🔌 indicando
   que `opentermx` está conectado.
   ![screenshot: claude_desktop_connected]
8. Probá: pedile "List my OpenTermX sessions". Debería invocar `list_sessions` y mostrarlas.

## Conectar Cursor en 5 minutos

1. Mismos pasos 1-4 que arriba, pero seleccionando **CURSOR** en el dropdown del snippet.
2. *Open folder* abre `~/.cursor/`. Editá `mcp.json` (o creá si no existe) y pegá el snippet.
   ![screenshot: cursor_mcp_config]
3. Reload window. El panel MCP en la sidebar derecha lista las 10 tools de OpenTermX.
4. Probá: "Usá inspect_session contra session-cisco y resumime los últimos errores".

## Conectar Cline en VS Code en 5 minutos

1. Pasos 1-4 con **CLINE** seleccionado.
2. *Open folder* abre el `globalStorage` de la extensión Cline. Pegá el snippet en
   `cline_mcp_settings.json`.
   ![screenshot: cline_settings]
3. En el panel de Cline, click en MCP Servers → debería aparecer `opentermx` con icono verde.
4. Probá: "Inspeccioná mis sesiones activas".

## Troubleshooting

### "Claude Desktop dice failed to connect"

- Confirmá que el servidor MCP está corriendo: en la status bar de OpenTermX deberías ver
  `🧠 MCP :127.0.0.1:8765` (verde).
- Si dice `MCP: failed (click for details)`, hacé click — te muestra el motivo (puerto en uso,
  permisos, etc).
- Probá `curl http://127.0.0.1:8765/mcp/health` desde una terminal — debería devolver `RUNNING`.
- Si configuraste un token, asegurate de que el snippet en `claude_desktop_config.json` lo
  contiene exactamente. Los espacios en `Bearer ` importan.

### "Puerto en uso"

- Otra app está usando 8765. Cambialo en *Setup → AI Assistant → MCP Server → Port* a, por
  ejemplo, 18765. Regenerá el snippet y reconfigurá tu cliente.

### "El LLM dice que no tiene tools"

- Verificá que el cliente está conectado: en Claude Desktop, mirá los logs en
  `~/Library/Logs/Claude/mcp*.log`. Buscá líneas con `opentermx`.
- Recordá: tras cambiar `claude_desktop_config.json`, **hay que reiniciar el cliente**.
- Si recién prendiste OpenTermX, dale 5 segundos al cliente para reconectar y reintentá.

### "El panel de aprobación no aparece"

- `propose_commands`, `run_macro`, `open_session` y `close_session` SIEMPRE abren el diálogo
  de aprobación. Si no aparece:
  - Mirá si OpenTermX está minimizado — el modal pop-up puede haber quedado oculto.
  - Verificá que no marcaste *Read-only mode* — eso hace que esas tools devuelvan error sin
    abrir el diálogo.
- En *Setup → AI Assistant → MCP Server*, activá *Verbose log* y volvé a probar: los logs
  de OpenTermX (`~/.opentermx/logs/opentermx.log`) van a mostrar el request crudo y por qué se
  rechazó o no.

### "El LLM ve credenciales en mis configs"

No debería: la **redacción de credenciales** está siempre activa. Si encontrás un patrón que
no detectamos (algún vendor específico con sintaxis rara para `password`), agregalo a
*Setup → AI Assistant → MCP Server → Custom redaction rules* como `(regex, reemplazo)`.

## Seguridad — modelo en una página

- **Bind por defecto a `127.0.0.1`**: solo conexiones locales pueden hablarle al servidor.
  Si necesitás acceso desde otra máquina, marcá *Enable TLS* y configurá el keystore. NUNCA
  bindeás a `0.0.0.0` sin TLS — el bearer token viajaría en plaintext.
- **Tokens hashed**: en `settings.json` solo guardamos hash SHA-256 del token, nunca el
  plaintext. Si te robaron el `settings.json`, te van a poder leer los hashes pero no el token.
- **Audit log**: cada llamada a `propose_commands` se loguea en `~/.opentermx/audit-ia.csv` con
  timestamp, sesión, vendor, comandos, decisión del operador, y output. Es append-only.
- **Rate limit + circuit breaker**: si un LLM cliente intenta abusar (60+ requests/min, o
  rechazos repetidos de `propose_commands`), el servidor le devuelve 429 y le bloquea el token
  por 10 minutos.
- **ACL por sessionId**: opcional. Si configurás `Allowed sessions (glob) = lab-*`, las tools
  rechazan cualquier intento de tocar sesiones que no matcheen ese glob.

## Comandos útiles

```bash
# Smoke test post-install: corre todas las tools con timeouts
python tools/mcp/smoke_test.py --url http://127.0.0.1:8765 --token TU_TOKEN

# Diff de schemas entre versiones (útil pre-release)
python tools/mcp/schema_diff.py before.json after.json
```

## Troubleshooting

Hallazgos recurrentes del pre-flight de T1 (testing manual). Si tropezás con algo de esto, mirá acá antes de abrir un issue.

### PowerShell muestra caracteres acentuados como `Ã³` o `Ã­`

**Causa:** la consola no está en UTF-8 por default; PowerShell interpreta los bytes UTF-8 que devuelve el servidor como Latin-1, así que `ó` se ve `Ã³` y `í` como `Ã­`.

**Fix puntual** (solo para esta sesión):

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
```

**Fix persistente:** agregalo a tu `$PROFILE`:

```powershell
notepad $PROFILE
# Pegá las dos líneas de arriba y guardá.
```

Reabrí PowerShell y debería resolverse para `curl`, `Invoke-RestMethod`, etc.

### Curl en PowerShell no funciona con JSON: error de parseo

**Causa:** PowerShell **no** interpreta `\"` como un escape — preserva el backslash literal. Así que `curl -d "{\"foo\":1}"` envía al server `{\"foo\":1}` con backslashes, y JSON parsing falla con `Unexpected character`.

**Fix recomendado** — usar `Invoke-RestMethod` (nativo de PowerShell, maneja JSON limpio):

```powershell
$body = @{
    jsonrpc = "2.0"
    id      = 1
    method  = "initialize"
    params  = @{ protocolVersion = "2024-11-05" }
} | ConvertTo-Json -Compress

Invoke-RestMethod -Uri "http://127.0.0.1:8765/mcp" `
    -Method Post `
    -Headers @{ "Authorization" = "Bearer TU_TOKEN" } `
    -ContentType "application/json" `
    -Body $body
```

**Fix alternativo** si necesitás sí o sí `curl.exe`: usar el stop-parsing token `--%`:

```powershell
curl.exe --% -X POST http://127.0.0.1:8765/mcp -H "Authorization: Bearer TU_TOKEN" -H "Content-Type: application/json" -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"
```

El `--%` le dice a PowerShell "no parseés nada de acá en adelante, mandalo literal".

### Cliente MCP responde `-32600` "Cliente no inicializado"

**Causa:** el spec MCP exige que el primer request sobre una sesión sea `initialize`. Clientes reales (Claude Desktop, Cursor, Cline) hacen esto solos; si estás probando manual con curl, lo tenés que hacer vos.

**Fix** — la danza de dos pasos:

```powershell
# 1) Inicializar y guardar la version negociada
$init = Invoke-RestMethod -Uri "http://127.0.0.1:8765/mcp" -Method Post `
    -Headers @{ "Authorization" = "Bearer TU_TOKEN" } `
    -ContentType "application/json" `
    -Body (@{ jsonrpc="2.0"; id=1; method="initialize"; params=@{ protocolVersion="2024-11-05" } } | ConvertTo-Json -Compress)

$version = $init.result.protocolVersion

# 2) Llamar la tool con el header MCP-Protocol-Version
Invoke-RestMethod -Uri "http://127.0.0.1:8765/mcp" -Method Post `
    -Headers @{ "Authorization" = "Bearer TU_TOKEN"; "MCP-Protocol-Version" = $version } `
    -ContentType "application/json" `
    -Body (@{ jsonrpc="2.0"; id=2; method="tools/list" } | ConvertTo-Json -Compress)
```

Si el segundo request va sin el header `MCP-Protocol-Version`, el server devuelve `-32600 Falta header MCP-Protocol-Version`.

### Cliente MCP no aparece en Claude Desktop después de configurar

**Causa más común:** Claude Desktop sigue corriendo en background después de cerrar la ventana — la nueva config no se carga hasta que matás el proceso entero.

**Fix:**

- **macOS:** `Cmd+Q` sobre la app (cerrar la ventana NO la cierra), o `killall "Claude"` en la terminal.
- **Windows:** click derecho en el icono de la tray → "Quit", o `taskkill /F /IM Claude.exe`.

Volvé a abrir y el servidor MCP debería aparecer en la lista.

**Causa secundaria:** JSON inválido en `claude_desktop_config.json`. Validalo:

```bash
jq . ~/Library/Application\ Support/Claude/claude_desktop_config.json   # macOS
jq . $env:APPDATA\Claude\claude_desktop_config.json                      # Windows (PowerShell)
```

Si `jq` falla, el JSON tiene un error — coma de más, comilla mal cerrada, etc. Arreglalo y reiniciá Claude Desktop.