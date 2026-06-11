# `integrations` — Conectores read-only a plataformas de monitoreo

Fase 4 del plan de telemetría: clientes **solo-lectura** para Zabbix y ManageEngine
OpManager, consumidos por las tools MCP `zabbix_get_history`, `zabbix_get_active_problems`,
`opmanager_get_alarms` y `opmanager_get_performance`. Sin JavaFX/Javalin; HTTP con
`java.net.http.HttpClient` y JSON con Jackson.

## Reglas comunes

- **Solo lectura**: no existe código que cree/cierre/acknowledgee problemas ni modifique
  hosts. Toda modificación a equipos sigue saliendo por `propose_commands`.
- Timeout HTTP 10 s; **2 reintentos con backoff solo para 5xx/transporte** — un 4xx no
  se reintenta.
- El secreto (token/apiKey) **jamás aparece** en logs, respuestas ni mensajes de error
  (`HttpSupport.scrub` lo enmascara como `***`).
- `verifyTls: true` por default; desactivarlo es explícito y deja warning en el log.
- Las respuestas de las tools marcan `contentOrigin: "external_monitoring_platform"` y
  envuelven todo en `data` — mitigación de prompt injection indirecta: el cliente MCP
  debe tratar esos textos como NO confiables.
- Truncado server-side: `data` serializado > 64 KB se recorta (listas más largas a la
  mitad) con `truncated: true`.

## Zabbix (`ZabbixClient`, JSON-RPC en `<base>/api_jsonrpc.php`)

- **Auth según versión** (trampa clásica): `apiinfo.version` (sin auth) decide —
  ≥ 6.4/7.x ⇒ header `Authorization: Bearer` (el campo `auth` en el body produce
  error); 5.x–6.0 ⇒ campo `auth` legacy. Override: `extra.apiVersion`. Si el secreto
  es `usuario:password`, en modo legacy se hace `user.login` y se cachea la sesión.
- **`history.get` exige el `history` = value_type del item** (si no, devuelve vacío
  sin error): siempre se resuelve antes con `item.get`.
- Rangos > 7 días van a `trend.get` (housekeeping borra history); `source` lo indica.
- `time_from`/`time_till` en **epoch segundos** (no milisegundos).

## OpManager (`OpManagerClient`, REST con `apiKey`)

Las rutas y campos **varían entre builds**: deserialización tolerante
(`FAIL_ON_UNKNOWN_PROPERTIES=false`, lectura por árbol, claves alternativas) y, si la
estructura no se reconoce, se devuelve el JSON original con `rawAvailable: true` en
lugar de romper. Paginación con `rowCount`/`fetchCount`; nunca se pide "todo".

## Configuración

`~/.opentermx/settings.json` → `monitoringIntegrations`:

```jsonc
{
  "monitoringIntegrations": [
    { "kind": "ZABBIX", "name": "zbx-lab", "baseUrl": "https://zabbix.lab", "verifyTls": true },
    { "kind": "OPMANAGER", "name": "opm-dc", "baseUrl": "https://opmanager.dc:8061" }
  ]
}
```

Token: campo `token` cifrado con SecretCipher, o env
`OPENTERMX_INTEGRATION_<NOMBRE>_TOKEN` (p. ej. `OPENTERMX_INTEGRATION_ZBX_LAB_TOKEN`).

## Tests

MockWebServer: ambos modos de auth de Zabbix (criterio de aceptación), resolución de
value_type, history vs trend, epoch en segundos, secreto nunca filtrado, OpManager
tolerante + fallback raw, reintentos 5xx / no-reintento 4xx.

```bash
./gradlew :integrations:test
```
