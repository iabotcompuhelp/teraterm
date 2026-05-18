# Snapshots pre/post + diff (Phase 3 Fase 4)

Capturan el estado de un device antes y después de un cambio para que el rol VALIDATOR pueda razonar sobre lo que efectivamente cambió y decidir si la operación tuvo éxito.

## Modelo conceptual

Un **snapshot** es un congelamiento de las últimas N líneas del buffer del `SessionRegistry` con metadata adicional (`operationId`, `deviceAlias`, `snapshotType`, `timestamp`, `contentHash` SHA-256, `label` opcional). La fuente del contenido es el buffer real de la sesión activa — esta tool **no** ejecuta comandos: el cliente LLM debe haber inyectado los comandos canónicos antes (vía `propose_commands`) y esperado la salida.

Esto evita lidiar con timing/async dentro del handler y mantiene la acción explícita: el cliente sabe cuándo está listo para "congelar".

## Storage

- Con operation activa: `~/.opentermx/operations/<op-id>/snapshots/<snap-id>.json`
- Sin operation: `~/.opentermx/snapshots/<snap-id>.json` (orphan)
- Backend swappable vía interface `SnapshotStore` (impls: `FsSnapshotStore`, `InMemorySnapshotStore`).

## Tools

| Tool | Rol | Input | Output |
|---|---|---|---|
| `snapshot_create` | OPERATOR, VALIDATOR | `{sessionId, snapshotType, lastLines?, deviceAlias?, label?}` | `{snapshotId, contentHash, lineCount, operationId}` |
| `snapshot_diff` | OPERATOR, COMPLIANCE, VALIDATOR | `{snapshotIdBefore, snapshotIdAfter, deviceType?}` | `{summary, addedLines, removedLines, identicalLineCount, sections}` |
| `snapshot_compare_to_criteria` | VALIDATOR | `{snapshotIdAfter, operationId?}` | `{overall, summary, results}` |
| `rollback_propose` | VALIDATOR | `{snapshotIdBefore, snapshotIdAfter, deviceType?}` | `{supported, commands, notes, deviceType}` |

### `snapshot_create`

Captura las últimas `lastLines` (default 500, máx 10 000) líneas del buffer. `snapshotType` es solo un label semántico — `running_config` / `interfaces_status` / `routing_table` / `custom` / `buffer_tail`. El contenido es siempre lo que esté en el buffer.

### `snapshot_diff`

Diff line-based. Detecta agregados / removidos / identicos.

Para `deviceType ∈ {cisco_ios, hpe_comware, huawei_vrp}` agrupa los cambios por **sección de config** (heurística: líneas en columna 0 = header, líneas indentadas = pertenecen al header anterior). Eso permite que el LLM razone sobre "interface Gi0/1 cambió" en vez de "líneas 47 a 53 cambiaron".

### `snapshot_compare_to_criteria`

Evalúa los `success_criteria` declarados en el operation context contra el snapshot post-cambio. Tipos soportados:

| Tipo | Pass cuando | Fields |
|---|---|---|
| `command_output_contains` | Regex `pattern` matchea (recortado desde la primera línea con `command` si se provee) | `command`, `pattern` |
| `no_interface_down` | Ningún match en el snapshot del regex de interface down (heurística multi-vendor) | — |
| `route_exists` | `destination` (literal) aparece en el snapshot | `destination` |

Tipo desconocido → `WARN` (no rompe la evaluación; el LLM lo ve y decide).

### `rollback_propose`

Genera una lista sugerida de comandos para revertir un device al estado de `snapshotIdBefore` desde `snapshotIdAfter`. **No ejecuta** — los devuelve al loop de operator/compliance.

Heurística por device_type:
- **cisco_ios / hpe_comware / huawei_vrp**: línea agregada → `no <línea>`. Si la línea ya empezaba con `no `, se quita la negación. Línea removida → reaplicar literalmente.
- **otros**: `supported=false` + notas explicativas. No inventamos comandos.

Limitaciones de la heurística documentadas en `notes`. El LLM siempre debe revisar antes de ejecutar.

## Integración con `propose_commands`

Cuando una operation tiene `constraints.require_snapshot: true`, el handler de `propose_commands` exige al menos un snapshot previo para el `sessionId` o `deviceAlias` del request. Si no hay → `INVALID_ARGUMENT` con mensaje "Capturalo con `snapshot_create` antes de ejecutar".

## Flujo end-to-end recomendado

```text
OPERATOR                              VALIDATOR                      Servidor
   │ start_operation                       │                           │
   │ (require_snapshot=true,               │                           │
   │  success_criteria=[...])              │                           │
   │──────────────────────────────────────────────────────────────────▶│
   │◀──────────────────────────────────────────────────────────────────│
   │                                       │                           │
   │ propose_commands(["show run"]) ───────────────────────────────────▶│  ejecuta `show running-config`
   │◀──────────────────────────────────────────────────────────────────│
   │                                       │                           │
   │ snapshot_create(type="running_config") ──────────────────────────▶│  guarda snapshot pre
   │◀── {snapshotId: snap-A}─────────────────────────────────────────── │
   │                                       │                           │
   │ propose_commands(["configure terminal", ...]) ───────────────────▶│  applies change
   │◀──────────────────────────────────────────────────────────────────│
   │                                       │                           │
   │ propose_commands(["show run"]) ───────────────────────────────────▶│  re-captura output
   │◀──────────────────────────────────────────────────────────────────│
   │                                       │                           │
   │ snapshot_create(type="running_config") ──────────────────────────▶│  guarda snapshot post
   │◀── {snapshotId: snap-B}─────────────────────────────────────────── │
   │                                       │                           │
   │       (validator toma el control)     │                           │
   │                                       │ snapshot_diff(A, B) ─────│
   │                                       │◀──{summary, sections}────│
   │                                       │                           │
   │                                       │ snapshot_compare_to_criteria(snap-B) ─▶│
   │                                       │◀──{overall: ALL_PASS|FAIL|PARTIAL}─────│
   │                                       │                           │
   │                                       │ (si FAIL)                 │
   │                                       │ rollback_propose(A, B) ──▶│
   │                                       │◀──{commands, notes}───────│
   │                                       │                           │
   │ (operator decide ejecutar el rollback o investigar más)           │
   │                                       │                           │
   │ end_operation ───────────────────────────────────────────────────▶│
```

## Whitelist actualizada (Fase 4)

| Rol | Snapshot tools |
|---|---|
| OPERATOR | `snapshot_create`, `snapshot_diff` |
| COMPLIANCE | `snapshot_diff` (para enriquecer su decisión) |
| VALIDATOR | `snapshot_create`, `snapshot_diff`, `snapshot_compare_to_criteria`, `rollback_propose` |

El VALIDATOR es el rol "dueño" del paso de verificación; el OPERATOR captura los snapshots pre/post porque es quien interactúa con la sesión.

## Limitaciones conocidas

- **Storage growth.** `~/.opentermx/operations/` puede crecer indefinido. No hay GC automático. Recomendación operativa: archivar/eliminar ops con `closed.json` más viejas que N días.
- **Rollback heurístico para vendors no listados.** `linux_shell`, `mikrotik_routeros`, `fortinet`, etc. caen al modo `supported=false`. Agregar adapters específicos es un PR concreto.
- **Las líneas idénticas con orden distinto** se cuentan como sin cambio. Si el orden importa (ej. ACLs), revisá `sections` para Cisco IOS, donde la posición se preserva.
- **`no_interface_down` es una heurística regex multi-vendor.** Puede tener falsos positivos en outputs que mencionan "down" en otros contextos. Cuando el vendor tenga un parser estructurado (Fase 5 hook `DeviceConfigParser`), se va a poder reemplazar.
- **El snapshot no es atómico vs el device.** Entre el momento que el operator ejecutó el comando y `snapshot_create`, el device pudo haber recibido output de otra fuente (operador humano, otra sesión). En la práctica esto es raro pero posible — el snapshot refleja el buffer del cliente, no el device real.
