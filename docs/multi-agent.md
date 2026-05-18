# Multi-agent roles + approval token (Phase 3 Fase 3)

OpenTermX modela tres "personas" LLM con responsabilidades separadas y whitelists de tools distintas. La separación de roles es la salvaguarda principal: aun si un LLM se comporta mal, el server enforce qué puede llamar.

## Los tres roles

| Rol | Qué hace | Whitelist (tools/call) |
|---|---|---|
| **OPERATOR** | Propone y ejecuta comandos. Decisiones de cambio. | `list_sessions`, `inspect_session`, `search_knowledge_base`, `list_macros`, `inventory_*`, `read_audit_log`, `start/end/current_operation`, `open_session`, `close_session`, `propose_commands`, `run_macro`, `tail_session` |
| **COMPLIANCE** | Evalúa propuestas. **No ejecuta**. Emite approval tokens. | `list_sessions`, `inspect_session`, `search_knowledge_base`, `inventory_*`, `read_audit_log`, `current_operation`, `compliance_evaluate` |
| **VALIDATOR** | Verifica el estado tras un cambio (snapshots de Fase 4). Read-only. | `list_sessions`, `inspect_session`, `search_knowledge_base`, `list_macros`, `inventory_*`, `read_audit_log`, `current_operation` |

El rol se declara por request vía header HTTP `X-OpenTermX-Role: OPERATOR|COMPLIANCE|VALIDATOR`. Sin header (o con valor desconocido) → default `OPERATOR` (back-compat con clientes pre-Fase 3).

**Enforcement server-side**: el dispatcher consulta `RoleAccessControl.allows(role, toolName)` antes de invocar el handler. Tools fuera del scope devuelven `-32601 Method not found` con mensaje "Tool `X` no permitida para rol `Y`". El catálogo de whitelists está hardcoded en `mcp-server/.../security/Role.kt` para que cualquier cambio sea un PR explícito (no un settings editable).

Los métodos de transporte MCP — `initialize`, `ping`, `tools/list`, `resources/list/read`, `prompts/list/get` — están siempre permitidos.

## Approval token

Cuando una operation declara `constraints.require_compliance_approval: true`, el `propose_commands` del rol OPERATOR exige un `approvalToken` firmado por COMPLIANCE. Sin token, o con token inválido, el handler rechaza ANTES del approval gate humano.

### Formato

```
base64url(json(payload)) + "." + base64url(hmacSHA256(payload))
```

Payload firmado:
```json
{
  "operationId": "op-…",
  "role": "COMPLIANCE",
  "deviceAlias": "core-router-1",
  "commandsHash": "sha256 hex del join('\\n')",
  "exp": 1735689600000,
  "nonce": "uuid"
}
```

### Secret HMAC

Archivo `~/.opentermx/mcp-secret.key` con 32 bytes random, auto-generado en el primer arranque del server. Permisos `0600` en filesystems POSIX (Linux, macOS); en Windows queda con los permisos default del directorio del usuario.

**Rotación:** borrar el archivo y reiniciar el server. Eso invalida todos los tokens emitidos previamente — la spec MCP no expone refresh.

### Verificación

El handler de `propose_commands` valida:

1. **Formato** — dos partes base64url separadas por `.`.
2. **Firma HMAC-SHA256** — usando el secret actual del server.
3. **`operationId`** — debe matchear una op activa con `require_compliance_approval`.
4. **`deviceAlias`** — si el token declara device, debe matchear el del request. Token sin device sirve para cualquiera.
5. **`exp`** — el token no debe estar expirado (default TTL 15 min, máx 60 min).
6. **`commandsHash`** — SHA-256 del join `\n` de los commands debe matchear lo que compliance firmó. Cualquier modificación de un comando invalida el token.

Si todo pasa, el flujo sigue al **approval gate humano** (que muestra el diálogo con el semáforo SAFE/CONFIG/DANGEROUS). El humano sigue siendo el último filtro; el approval token es complementario, no reemplazo.

## Flujo end-to-end

```text
Cliente OPERATOR                Cliente COMPLIANCE            Servidor MCP
       │                                │                          │
       │ start_operation                │                          │
       │ (require_compliance_approval) ─┼─────────────────────────▶│  persist, return op-id
       │◀────────────────────────────── │                          │
       │                                │                          │
       │ propose_commands SIN token ────┼─────────────────────────▶│  ❌ rechaza (operation exige token)
       │◀────────────────────────────── │                          │
       │                                │                          │
       │     (operator comparte op-id + propuesta con compliance,  │
       │      out-of-band, fuera del MCP server)                   │
       │                                │                          │
       │                                │ compliance_evaluate ─────│
       │                                │ (op-id, commands)       │  firma HMAC, audita decisión
       │                                │◀────────────────────────│   { approvalToken, reasons }
       │                                │                          │
       │     (compliance pasa token al operator out-of-band)       │
       │                                │                          │
       │ propose_commands CON token ────┼─────────────────────────▶│  ✓ verify token →
       │                                │                          │   ApprovalGate humano →
       │                                │                          │   inject commands → audit
       │◀────────────────────────────── │                          │
       │                                │                          │
       │ end_operation ─────────────────┼─────────────────────────▶│  close
```

Cada paso queda registrado en `~/.opentermx/audit-ia.csv`: el `compliance_evaluate` con `sessionId=compliance:<op-id>` y el `propose_commands` con su propio entry. Cruce sencillo por `operationId`.

## Tradeoff de implementación: single-LLM vs 3 procesos

**Implementación actual (Fase 3):** un único cliente LLM cambia de "persona" mandando el header `X-OpenTermX-Role` distinto en cada request. Es la opción más simple — un solo proceso, un solo contexto LLM, un solo MCP client.

**Evolución posible (no implementada):** tres procesos separados, cada uno con su propio system prompt y su propia conexión MCP. Las dos sessions HTTP del MCP server quedan auditadas por separado.

| Aspecto | Single-LLM | 3 procesos |
|---|---|---|
| Setup | 1 cliente MCP | 3 clientes MCP |
| System prompt | Conmuta por request | Estable por proceso |
| Aislamiento de contexto LLM | No (mismo proceso lee todo) | Sí |
| Cost | 1× | 3× tokens |
| Coordinación | Out-of-band (string passing) | Out-of-band (string passing) |
| Auditabilidad por rol | Por header HTTP | Por sessionKey distinta |

Para empezar, el single-LLM es suficiente — el enforcement server-side de las whitelists garantiza que el comportamiento desde el punto de vista del server es idéntico. Si se necesita aislamiento real del contexto LLM (un compliance que NUNCA vio lo que el operator vio), conviene migrar a 3 procesos.

## Configuración del cliente

### Single-LLM con persona conmutada

Antes de cada llamada, el cliente setea el header del rol que corresponda:

```python
def call_as(role: str, method: str, params: dict):
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "MCP-Protocol-Version": NEGOTIATED_VERSION,
        "X-OpenTermX-Role": role,
    }
    return httpx.post(URL, json={"jsonrpc":"2.0","id":1,"method":method,"params":params}, headers=headers)
```

### Claude Desktop / Cursor / Cline

Para clientes que no permiten setear headers custom dinámicamente, configurá **dos servers MCP separados** (mismo URL, distinto label), cada uno con un `X-OpenTermX-Role` distinto en su `headers` de config. El LLM elige cuál llamar.

```jsonc
// claude_desktop_config.json
{
  "mcpServers": {
    "opentermx-operator": {
      "url": "http://127.0.0.1:8765/mcp",
      "headers": {
        "Authorization": "Bearer <token>",
        "X-OpenTermX-Role": "OPERATOR"
      }
    },
    "opentermx-compliance": {
      "url": "http://127.0.0.1:8765/mcp",
      "headers": {
        "Authorization": "Bearer <token>",
        "X-OpenTermX-Role": "COMPLIANCE"
      }
    }
  }
}
```

## Invariantes

- **El enforcement de roles es server-side.** No alcanza con prompt-engineering.
- **El humano sigue siendo el último filtro.** El approval token complementa, no reemplaza, el approval gate del `propose_commands`.
- **El token NO se persiste.** El cliente debe almacenarlo en memoria mientras dure la operación.
- **Token expirado, de otra op, o con commands distintos → rechazo automático con mensaje claro.**
- **Secret en `~/.opentermx/mcp-secret.key` con permisos `0600`** cuando el OS lo soporta.

## Limitaciones conocidas

- **Sin rotación automática del secret.** Hay que borrar el archivo a mano y reiniciar.
- **Single tenant.** El secret es global al server; si en el futuro el server expone a más de un operador, el secret debería ser per-operator.
- **Coordinación out-of-band entre operator y compliance.** El MCP server no transfiere el token de uno a otro automáticamente — el cliente LLM tiene que mantenerlo en su contexto y reenviarlo.
- **Sólo `propose_commands` consume approval token.** Las otras tools mutativas (`run_macro`, `open_session`, `close_session`) van directo al approval gate humano. Fase 4 va a evaluar si extender el contrato a `run_macro` también.
