# Plan de validación con clientes MCP reales

Este documento lo ejecuta una persona — no Claude Code — para validar que el servidor MCP de OpenTermX se integra correctamente con clientes públicos. La instrumentación de logging está implementada en `mcp-server` (T1 de phase 2); este plan dice cómo usarla.

## Pre-requisitos

1. OpenTermX corriendo en una versión que incluya el módulo `mcp-server`.
2. `Setup → AI Assistant → MCP Server`:
   - **Enabled** marcado.
   - **Verbose log** marcado (genera logs DEBUG con request/response completos).
   - **Token** generado y copiado al portapapeles.
3. Cliente bajo prueba instalado (Claude Desktop / Cursor / Cline / Continue).
4. Logback configurado para mostrar nivel DEBUG en `com.opentermx.mcp` — editar `logback.xml` si hace falta.
5. Al menos una sesión activa para probar (cualquier SSH/Telnet/Serial sirve — `session-cisco` mock alcanza).

## Por cliente

### Claude Desktop

| OS      | Path del config                                            |
|---------|------------------------------------------------------------|
| macOS   | `~/Library/Application Support/Claude/claude_desktop_config.json` |
| Windows | `%APPDATA%\Claude\claude_desktop_config.json`              |
| Linux   | `~/.config/Claude/claude_desktop_config.json`              |

Snippet (reemplazar `TU_TOKEN`):

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

Pasos:
1. Pegar el snippet, reiniciar Claude Desktop.
2. Abrir una conversación nueva. Verificar que aparezca el icono "🔌" indicando que opentermx está conectado.
3. Pedirle a Claude: "List my OpenTermX sessions". Debería invocar `list_sessions` y mostrar las sesiones activas.
4. Pedirle: "Inspect session-cisco last 20 lines" — debería invocar `inspect_session`.
5. Pedirle algo que dispare `propose_commands` — por ejemplo "Show the IOS version on session-cisco". Verificar que se abre el diálogo de aprobación en OpenTermX.

### Cursor

Path del config: `~/.cursor/mcp.json` (mismo formato que Claude Desktop).
Pasos: equivalentes a los de Claude Desktop. Cursor surfacea las tools en el panel lateral derecho.

### Cline (extensión de VS Code)

VS Code → Cline panel → settings (⚙) → MCP Servers → Add. Mismo formato JSON. Reload window.
Pasos: equivalentes.

### Continue (extensión de VS Code/JetBrains)

`~/.continue/config.json` → seccion `mcpServers`.

## Qué loguear

Para cada cliente probado, capturar:

1. **`~/.opentermx/logs/opentermx.log`** — buscar entradas `MCP[verbose] ⇒` y `MCP[verbose] ⇐`. Confirmar que:
   - El header `Authorization: Bearer ...` viene exactamente igual que el token configurado.
   - El primer request es `initialize` con `protocolVersion` que el cliente declara soportar.
   - Los requests siguientes traen header `MCP-Protocol-Version` (cuando T2 esté implementado).
2. **Logs del propio cliente** — Claude Desktop guarda en `~/Library/Logs/Claude/mcp*.log` (macOS) o equivalente. Buscar errores de conexión.
3. **Screenshot** del diálogo de aprobación cuando dispare `propose_commands` — útil para `USER_GUIDE.md` (T17).

## Qué reportar

Por cada cliente, completar una línea en este formato:

```
[YYYY-MM-DD] cliente=X versión=Y OS=Z resultado=OK|FAIL detalles=...
```

Si falla:
- Adjuntar el snippet del log donde se ve el request crudo y el response.
- Anotar a qué tool intentaba acceder y qué respuesta recibió.
- Si el cliente no soporta el transporte HTTP/SSE (algunos solo stdio), anotar y reintentar cuando T3 (stdio proxy) esté listo.

## Esperado

- **Claude Desktop ≥ 0.10**: soporta streamable HTTP, debería andar directo.
- **Cursor ≥ 0.45**: soporta HTTP, idéntico patrón.
- **Cline ≥ 3.0**: HTTP soportado.
- **Continue ≥ 0.9**: HTTP soportado.

Si alguno NO conecta vía HTTP, T3 (stdio proxy) cubre ese caso.

## Resultado

Resultado de la primera ronda de validación (poblar tras correr el plan):

```
[fecha] Claude Desktop = pendiente
[fecha] Cursor         = pendiente
[fecha] Cline          = pendiente
[fecha] Continue       = pendiente
```