# Operation Context (Phase 3 Fase 1)

Una **operation** es una intención estructurada que un cliente LLM declara antes de empezar a operar sobre devices. El servidor MCP la usa para tres cosas a la vez:

1. **Inyectar el context** en cada `tools/call` exitoso mientras la operation esté activa. El LLM recibe en cada turno un recordatorio compacto (`scope.devices`, `forbidden_commands`, `success_criteria`, etc.) sin tener que recordar el detalle.
2. **Bloquear comandos prohibidos** antes de que toquen el device. Si una tool con argumento `commands` (`propose_commands` por ahora) recibe un comando que matchea `scope.forbidden_commands` o que cae fuera de `scope.allowed_commands_prefix`, el dispatcher devuelve `isError: true` con razón explícita y el handler nunca se invoca.
3. **Persistir el estado** en `~/.opentermx/operations/<op-id>/context.json` (con `closed.json` cuando se cierra), de modo que un restart del server recupera las operations abiertas y las expone vía `current_operation`.

## Esquema del context

El schema formal vive en [`mcp-server/src/main/resources/schemas/operation-context.schema.json`](../mcp-server/src/main/resources/schemas/operation-context.schema.json). Ejemplo:

```yaml
operation:
  id: "op-2026-05-17-ospf"            # opcional; auto-generado si falta
  description: "Activar OSPF area 0"
  initiated_by: "operator@example.com"
scope:
  devices: ["core-router-1"]
  allowed_commands_prefix:            # opcional. Vacío = sin restricción de prefijo.
    - "show"
    - "configure terminal"
    - "router ospf"
  forbidden_commands:                 # substring match case-insensitive
    - "reload"
    - "erase"
    - "factory-reset"
success_criteria:
  - type: "command_output_contains"
    command: "show ip ospf neighbor"
    pattern: "FULL"
constraints:
  max_duration_minutes: 15
  require_compliance_approval: true   # consumido por Fase 3
  require_snapshot: true              # consumido por Fase 4
```

Ejemplo completo: [`examples/operation-context.example.yaml`](../examples/operation-context.example.yaml).

## Tools

| Tool | Rol | Input |
|---|---|---|
| `start_operation` | Inicia una op | exactamente uno de `contextPath` (path al .yaml/.yml/.json), `contextInline` (objeto JSON) o `contextYaml` (string YAML) |
| `end_operation` | Cierra la op activa | `operationId` |
| `current_operation` | Recupera la op activa de la sesión MCP | sin args |

`start_operation` valida el context contra el JSON Schema antes de aceptarlo. Errores de schema llegan al cliente como `isError: true` con texto del estilo:

```
Operation context inválido:
  - #/operation: required key [description] not found
```

## Flujo end-to-end

```text
Cliente LLM                                  Server MCP
    │                                            │
    │── start_operation(contextInline=…) ───────▶│  valida schema, persiste, indexa por sessionKey
    │◀── {operationId: "op-…", …} ───────────────│
    │                                            │
    │── tools/call(name="list_sessions") ───────▶│
    │◀── result.content[0].text = ───────────────│
    │     [OPERATION CONTEXT op-…]               │
    │     description: …                         │
    │     forbidden_commands: …                  │
    │     ---                                    │
    │     <payload JSON original>                │
    │                                            │
    │── tools/call(propose_commands,            ─▶│  scope.validateCommand(cada cmd)
    │     commands=["show ver", "reload"])       │  → "reload" matchea forbidden → bloquea
    │◀── result.isError=true, text="Comando ─────│
    │     bloqueado por scope.forbidden_…"       │
    │                                            │
    │── end_operation(operationId=…) ───────────▶│  marca closed.json, libera sessionKey
    │◀── {operationId, durationMillis, …} ───────│
```

Tras `end_operation`, las próximas tools de la misma session MCP ya no llevan el bloque inyectado.

## Invariantes de la implementación

- **Una operation a la vez por `sessionKey` MCP.** Intentar `start_operation` con una op ya activa devuelve `isError` con mensaje claro. Para iniciar otra hay que `end_operation` la anterior.
- **`operationId` único globalmente.** Si dos clientes intentan el mismo id, el segundo es rechazado.
- **Recovery tras restart.** Las operations sin `closed.json` se restauran en memoria. Sus session bindings, en cambio, NO se restauran — el cliente que vuelva a conectar debe hacer su propio `start_operation` (o consultar `current_operation`). Esto evita rebindings implícitos.
- **El bloque inyectado es ADITIVO al wire MCP.** Un cliente que no entiende el bloque lo ve como texto plano antes del payload JSON. El separador `---\n` marca el corte.

## Limitaciones conocidas

- Por ahora el filtro de comandos solo aplica a tools cuyo input tenga un campo `commands: [String]`. `run_macro` no se inspecciona (los macros se ejecutan como bloque). Fase 3 va a complementar con el approval token, que cubre el caso macro.
- `success_criteria` se persiste pero no se evalúa automáticamente todavía. Va a ser consumido por la Fase 4 (`snapshot_compare_to_criteria`) y la Fase 3 (rol Validator).
- `constraints.max_duration_minutes` se guarda pero no dispara cierre automático. Es informativo por ahora.
