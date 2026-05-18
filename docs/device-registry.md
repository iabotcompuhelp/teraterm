# Device Registry (Phase 3 Fase 2)

El **Device Registry** permite que el cliente LLM trabaje con **nombres lógicos** (`core-router-1`) en vez de pasar `host/port/credentials` en cada llamada. La resolución la hace el servidor: el LLM nunca ve passwords.

A diferencia de un `inventory.yaml` separado, OpenTermX **extiende `SavedConnections`** (que ya tenía credenciales cifradas con `SecretCipher` y persistencia en `~/.opentermx/settings.json`). No hay archivo paralelo ni migración: los usuarios existentes simplemente ganan 4 campos opcionales en sus connections.

## Campos nuevos en `SavedConnection`

| Campo | Tipo | Rol |
|---|---|---|
| `alias` | `String?` | Nombre lógico único (`core-router-1`). **Solo entries con alias son inventory** — sin alias, una saved connection sigue funcionando como antes pero no aparece en `inventory_list`. |
| `tags` | `List<String>` | Etiquetas free-form para filtrar (`["core", "lab", "site-a"]`). |
| `groups` | `List<String>` | Agrupaciones (`["cores", "ospf-area-0"]`). Mismo contrato semántico que `tags`, separados para que la UI los muestre diferenciados. |
| `deviceType` | `String?` | Recomendado: `cisco_ios`, `huawei_vrp`, `hpe_comware`, `fortinet`, `linux_shell`, `mikrotik_routeros`. Se mantiene como `String` libre — la Fase 5 (Policy engine) lo va a usar para matchear policies por device type. |

Las credenciales (`secret`, `keyPath`) NO cambian — siguen cifradas con `SecretCipher` y nunca salen del módulo `:app`.

## Tools nuevas

| Tool | Rol | Input | Output |
|---|---|---|---|
| `inventory_list` | Lista devices con filtros AND | `{tags?, groups?, deviceType?}` | `{devices: [{alias, protocol, host, port, username, deviceType, tags, groups, displayLabel, hasActiveSession}]}` |
| `inventory_describe` | Metadata + sesión activa por alias | `{alias}` | `{device, activeSessionId}` |

**Invariante de seguridad:** ninguna de estas tools devuelve `secret`, `keyPath`, `password` ni equivalentes. Hay un test estructural en `SavedConnectionInventoryTest.InventoryDevice_no_expone_secret_ni_keyPath_estructuralmente` que rompe si alguien agrega un field con esos nombres al DTO.

## Extensión de `open_session`

El input schema ahora acepta `deviceAlias` como **alternativa a `protocol+host`**. Si viene:

- `protocol`, `host`, `port`, `username` se resuelven desde el Device Registry e ignoran lo que el cliente haya pasado.
- `credentialRef` se resuelve al `savedConnectionId` (el `SessionOpener` lo usa para descifrar las credenciales reales).
- `label` se hereda del `displayLabel` del inventory si el cliente no pasa uno.

Si el alias no existe → `McpToolException(NOT_FOUND, "deviceAlias `X` no existe en el inventario")` — error temprano y claro.

Si el cliente NO pasa `deviceAlias` ni `protocol`, el handler devuelve `INVALID_ARGUMENT` con mensaje pidiendo uno de los dos. Si pasa ambos, `deviceAlias` gana (documentado en la `description` del tool).

## UI Setup → Saved Connections

El diálogo gana 4 columnas editables inline:

| Columna | Edición |
|---|---|
| Alias | Texto libre. **Validación de unicidad**: si el operador escribe un alias que ya existe en otra entrada, la edición se rechaza silenciosamente (la fila revierte). |
| Tipo | Texto libre. Vacío = `null`. |
| Tags (csv) | Coma-separados; espacios alrededor se trimean. |
| Grupos (csv) | Idem. |

## Migración

- **Settings existentes:** sin acciones. Jackson deserializa los 4 campos como null/empty. Hasta que el operador edite, las entradas no son inventory (sin alias).
- **Settings nuevos:** los 4 campos default a null/empty. El operador opta-in editando.
- **Tests:** `SavedConnectionInventoryTest.JSON_sin_los_campos_nuevos_deserializa_con_defaults` cubre roundtrip de JSON pre-Fase 2.

## Flujo end-to-end

```text
Operator                                Cliente LLM (MCP)              Servidor
   │                                          │                            │
   ├ Setup → Saved Connections                │                            │
   │   • alias = "core-router-1"              │                            │
   │   • tags = ["core","lab"]                │                            │
   │   • deviceType = "cisco_ios"             │                            │
   │   (credenciales como siempre)            │                            │
   │                                          │                            │
   │                              inventory_list(tags=["core"])           │
   │                                          ├─────────────────────────▶ │
   │                                          │◀─ devices=[{alias=core-…, │
   │                                          │     host=…, no secret}]   │
   │                                          │                            │
   │                              open_session(deviceAlias="core-router-1")
   │                                          ├─────────────────────────▶ │
   │  approval dialog ← ───────────────────── │                            │ resuelve protocol+host+port+username+credentialRef
   │  OK ────────────────────────────────────▶│                            │ del registry, dispara SessionOpener
   │                                          │◀─ {approved:true,         │
   │                                          │     sessionId:"…"}        │
```

## Limitaciones conocidas

- **Validación de unicidad solo en la UI.** Si dos paths externos (import de snapshot, edición manual del JSON) generan colisión, `SavedConnections.findByAlias` devuelve `null` para evitar resolución ambigua. `SavedConnections.aliasCollisions` lo detecta — se puede usar como pre-flight check.
- **Resolución por alias es O(N).** Para inventarios de cientos de devices conviene cachear; por ahora no hace falta. El método `aliasIndex` se reserva en la API si llegamos a necesitarlo.
- **`deviceType` no valida valores.** La Fase 5 va a documentar los valores reconocidos por las policies; antes de eso, el operador puede escribir lo que quiera.
- **`groups` y `tags` son funcionalmente equivalentes hoy.** La separación es para la UI; ningún consumidor las trata distinto. Si en el futuro los grupos cambian de semántica (ej. herencia de policies de Fase 5), se puede divergir sin romper.
