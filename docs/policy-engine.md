# Policy engine determinístico (Phase 3 Fase 5)

Motor de reglas declarativas que evalúa snapshots de devices **sin LLM**. Misma config + misma policy → mismo resultado, byte-a-byte. Sirve para dos cosas:

1. **Enriquecer al rol COMPLIANCE** (Fase 3) con hechos determinísticos antes de firmar un `approval_token`.
2. **Auditorías masivas** sobre N devices del Device Registry sin involucrar el LLM.

Vive en el módulo Gradle nuevo `:policy-engine`, consumido por `:mcp-server`. Independiente del transporte MCP: cualquier herramienta CLI futura puede usarlo directo.

## Esquema YAML

Schema formal en [`policy-engine/src/main/resources/schemas/policy.schema.json`](../policy-engine/src/main/resources/schemas/policy.schema.json) (Draft-07).

```yaml
policy:
  name: "baseline-security-cisco-ios"   # único; regex [a-zA-Z0-9._-]{3,80}
  version: "1.0"
  applies_to:                           # opcional; usado por policy_audit para filtrar devices
    device_types: ["cisco_ios"]
    tags_any: ["core", "edge"]

rules:                                  # mínimo 1
  - id: "no-telnet"                     # regex [a-zA-Z0-9._-]{1,80}
    severity: "high"                    # low | medium | high | critical
    type: "pattern_deny"                # pattern_deny | require | recommend
    target: "running_config"            # running_config | routing_table | interfaces_status
    pattern: '^\s*transport\s+input\s+.*telnet'
    message: "Telnet inbound prohibido en transports de VTY."
```

Ejemplo completo: [`policies/baseline-security-cisco-ios.example.yaml`](../policies/baseline-security-cisco-ios.example.yaml).

## Tipos de regla

| Tipo | PASS cuando | FAIL/WARN cuando | Severidad de falla |
|---|---|---|---|
| `pattern_deny` | Ninguna línea matchea el regex | Cualquier línea matchea | `FAIL` |
| `require` | Al menos una línea matchea | Ninguna matchea | `FAIL` |
| `recommend` | Al menos una línea matchea | Ninguna matchea | `WARN` |

Tipo desconocido o regex inválida → `WARN` (no rompe la evaluación; el operador ve el detalle y decide).

## Tools

| Tool | Rol(es) | Input | Output |
|---|---|---|---|
| `policy_load` | COMPLIANCE | `{path?}` o `{yaml?}` | `{name, version, ruleCount}` |
| `policy_list` | COMPLIANCE, VALIDATOR | — | `{policies: [...]}` |
| `policy_evaluate` | COMPLIANCE, VALIDATOR | `{policyName, deviceAlias?|snapshotId?, markdown?}` | `{passCount, failCount, warnCount, results, markdown?}` |
| `policy_audit` | VALIDATOR | `{policyName, tagsAny?, markdown?}` | `{deviceCount, totalFail, totalWarn, byDevice, markdown?}` |

### Whitelist de roles

- **COMPLIANCE** carga policies (`policy_load`), las consulta (`policy_list`) y las evalúa contra un device puntual (`policy_evaluate`) — el resultado le sirve como insumo para su decisión sobre un `approval_token`.
- **VALIDATOR** puede listar, evaluar y además auditar la flota completa (`policy_audit`). El audit masivo es propio del flujo post-cambio.
- **OPERATOR** no puede tocar policies: tendría conflicto de interés (es el rol que ejecuta los cambios).

### `policy_evaluate`

Corre la policy contra el **snapshot más reciente** del `deviceAlias` (o contra el `snapshotId` que se le pase explícito). Si no hay snapshot previo del device → `NOT_FOUND` con mensaje "Capturalo con `snapshot_create`".

El `DeviceConfigParser` registrado para el `deviceType` del device transforma las líneas antes de evaluar. Default = identidad (split por `\n`).

### `policy_audit`

Recorre el inventory aplicando filtros AND:
- `applies_to.device_types` de la policy (match case-insensitive contra `SavedConnection.deviceType`).
- `applies_to.tags_any` de la policy (ANY-of contra `SavedConnection.tags`).
- `tagsAny` del input (ANY-of, AND con los filtros anteriores).

Para cada device matched evalúa contra su snapshot más reciente. Si no hay snapshot → todas las rules quedan como `WARN` con mensaje "Sin snapshot — capturar con snapshot_create".

Reporte JSON estructurado por device + opcional Markdown listo para pegar en ticket.

## Integración con Fase 3 (compliance)

El handler de `compliance_evaluate` puede invocar `policy_evaluate` internamente para enriquecer su decisión. Esa integración la decide el cliente LLM (compliance) — el server no la fuerza. Pseudo-código del lado del LLM:

```
1. policy_evaluate(policyName="baseline-security-cisco-ios", deviceAlias="core-router-1")
2. si failCount > 0 → razonar sobre los FAIL y decidir approve/reject
3. compliance_evaluate(operationId=…, proposedCommands=…, approved=…, reasons=[…])
```

## Hook `DeviceConfigParser`

Interface vacía con default identidad. Pensada para que un módulo futuro registre parsers estructurados por `deviceType` sin tocar `RuleEvaluator`. Ejemplo de uso:

```kotlin
DeviceConfigParsers.register("cisco_ios", object : DeviceConfigParser {
    override fun lines(rawContent: String): List<String> =
        rawContent.lines().filterNot { it.trimStart().startsWith("!") }
})
```

No se incluye ningún parser específico en esta fase — los regex de las rules ya manejan bien `running-config` plana.

## Determinismo y reproducibilidad

- Sin LLM en ninguna parte del motor.
- Sin estado mutable entre evaluaciones (la `PolicyRegistry` es solo cache de policies cargadas).
- Mismo snapshot + misma policy + mismo parser → mismo set de `RuleResult` con los mismos números de línea.

Esto importa porque el output va a `audit_log` y se referencia en tickets — los reportes tienen que ser reproducibles meses después.

## Limitaciones conocidas

- **Sin persistencia de policies.** El restart del server limpia el registry; hay que volver a cargar. Es trivial agregar storage si llega a hacer falta.
- **Target solo aplica como label**, no como filtro real — `RuleEvaluator` corre todas las rules contra el snapshot completo. Cuando lleguen snapshots multi-target (routing_table separado de running_config), se va a poder filtrar por `rule.target`.
- **Regex puro** — los parsers estructurados son opt-in y por ahora nadie los implementa. Para detección de patrones complejos (ej. "todas las interfaces edge tienen ACL", que requiere parsing de bloques) hace falta un parser específico.
- **`pattern_deny` para en la primera coincidencia.** Si la config tiene 10 líneas que violan, el reporte solo muestra la primera. Esto es deliberado — basta una para FAIL — pero si querés enumerar todas, conviene dividirlas en rules más específicas.
- **El audit recorre TODO el inventory.** Para flotas grandes (cientos de devices) puede ser lento; en ese caso conviene filtrar con `tagsAny`.
