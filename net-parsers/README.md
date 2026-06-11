# `net-parsers` — Parsing estructurado de output de equipos de red

Módulo Kotlin **puro** (sin JavaFX, sin Javalin, sin coroutines): convierte el output
crudo de comandos de interfaces en el modelo canónico `InterfaceStats`, independiente
del vendor. Es la Fase 2 del plan de telemetría; `mcp-server` lo consume para las tools
`get_interface_stats`, `get_link_status` y `get_bandwidth_utilization`.

## Modelo

- `Vendor` — enum canónico alineado 1:1 con el tipo `vendor_t` del esquema PostgreSQL
  de la Fase 3 (`CISCO_IOS`, `CISCO_NXOS`, `ARUBA_AOSCX`, `FORTINET`, `HUAWEI_VRP`,
  `MIKROTIK`, …). No confundir con el `Vendor` de detección de CLI de `ai-assistant`.
- `InterfaceStats` — 18 campos canónicos (estado, velocidad, duplex, MTU, tasas,
  contadores, drops, CRC, lastFlap) + `raw` para extras vendor-specific (hoy
  `rxBytes`/`txBytes`, que alimentan el método counter_delta de bandwidth). Contadores
  en `Long`: varios equipos superan 2^32.
- `ParseResult` — `Success` / `PartialSuccess(data, warnings)` / `Failure(reason,
  rawSample)`. **Regla de oro:** los parsers NUNCA lanzan; campo ausente ⇒ `null`,
  output irreconocible ⇒ `Failure` con las primeras 500 chars del crudo.

## Parsers

| Parser | Vendor | Comando |
|---|---|---|
| `CiscoIosShowInterfacesParser` | CISCO_IOS / CISCO_IOSXE | `show interfaces` |
| `NxosShowInterfaceParser` | CISCO_NXOS | `show interface` |
| `HuaweiDisplayInterfaceParser` | HUAWEI_VRP | `display interface` |
| `ArubaCxShowInterfaceParser` | ARUBA_AOSCX | `show interface` |
| `FortinetGetSystemInterfaceParser` | FORTINET | `get system interface` (solo estado ⇒ PartialSuccess) |
| `FortinetDiagNicParser` | FORTINET | `diagnose hardware deviceinfo nic <if>` |
| `MikrotikPrintStatsParser` | MIKROTIK | `/interface print stats without-paging` |
| `MikrotikEthernetMonitorParser` | MIKROTIK | `/interface ethernet monitor <if> once` |

`ParserRegistry` resuelve comando⇄parser por vendor (`interfaceStatsCommand`,
`forCommand`). `OutputCleaner` (mudado desde `mcp-server` en esta fase) limpia el
stream antes de parsear: decode UTF-8 tolerante, ANSI, backspaces, paginadores,
syslog asíncrono, eco y prompt final.

## Tests

Fixture-driven: cada `src/test/resources/fixtures/<vendor>/<caso>.txt` se compara
contra su `<caso>.expected.json` con JSONAssert **STRICT** — para ampliar cobertura
basta soltar un par nuevo (sanitizar IPs/seriales reales antes de commitear). Los
fixtures de `_dirty/` contienen streams crudos (ESC, backspaces, Latin-1 inválido)
y ejercitan el `OutputCleaner`. Además: test de basura (ningún parser lanza) y de
contadores > 32 bits.

```bash
./gradlew :net-parsers:test
```
