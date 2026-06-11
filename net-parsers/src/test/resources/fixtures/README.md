# Fixtures de prueba — Fase 2 (parsing estructurado por vendor)

Copiar este directorio a `net-parsers/src/test/resources/fixtures/` del repositorio OpenTermX.

## Convención

Cada fixture viene en pares:

- `<comando>_<os>_<caso>.txt` — output del equipo, **ya limpio** (post-OutputCleaner: sin paginador, sin ANSI, sin eco, LF). Es la entrada del parser.
- `<comando>_<os>_<caso>.expected.json` — resultado canónico esperado, con la forma exacta del `outputSchema` de `get_interface_stats` (modelo `InterfaceStats`).

Los archivos en `_dirty/` son la excepción: contienen el stream **crudo** del socket (con bytes de control reales: ESC, backspaces, CRLF, Latin-1 inválido) y prueban el `OutputCleaner`, no el parser. Ver `_dirty/README.md`.

## Mapeo fixture → parser → comando real

| Carpeta | Fixture | Parser | Comando en el equipo |
|---|---|---|---|
| cisco_ios | show_interfaces_ios15.2_basic | CiscoIosShowInterfacesParser | `show interfaces` |
| cisco_ios | show_interfaces_ios12.4_errdisabled | CiscoIosShowInterfacesParser | `show interfaces` |
| cisco_nxos | show_interface_nxos9.3_up | NxosShowInterfaceParser | `show interface` |
| cisco_nxos | show_interface_nxos7.0_down | NxosShowInterfaceParser | `show interface` |
| huawei_vrp | display_interface_vrp8_up | HuaweiDisplayInterfaceParser | `display interface` |
| huawei_vrp | display_interface_vrp5_admindown | HuaweiDisplayInterfaceParser | `display interface` |
| aruba_aoscx | show_interface_aoscx10.10_up | ArubaCxShowInterfaceParser | `show interface` |
| aruba_aoscx | show_interface_aoscx10.06_down | ArubaCxShowInterfaceParser | `show interface` |
| fortinet | diag_hw_nic_port1_fortios7.4 | FortinetDiagNicParser | `diagnose hardware deviceinfo nic port1` |
| fortinet | get_system_interface_fortios7.2 | FortinetGetSystemInterfaceParser | `get system interface` |
| mikrotik | interface_print_stats_ros7 | MikrotikPrintStatsParser | `/interface print stats without-paging` |
| mikrotik | interface_ethernet_monitor_ros6.49 | MikrotikEthernetMonitorParser | `/interface ethernet monitor ether1 once` |

## Casos límite cubiertos deliberadamente

1. **Estados**: UP/UP, admin down (`administratively down` IOS, `Administratively DOWN` Huawei, flag `X` MikroTik), oper down con admin up (NX-OS `Link not connected`, AOS-CX `Waiting for link`), y `err-disabled` (IOS 12.4).
2. **Velocidades**: `1000Mb/s` (IOS), `10 Gb/s` con espacio (NX-OS), `Speed 10000 Mb/s` (AOS-CX), `Speed :  1000` sin unidad = Mbps (Huawei), `10000full` pegado (Fortinet), `1Gbps` (MikroTik). El parser debe normalizar todas a bps.
3. **Auto-speed/Auto-duplex**: cuando el puerto está caído, IOS/NX-OS/AOS-CX reportan `Auto-speed` → `speedBps: null`, `duplex: "AUTO"`. No inventar la velocidad nominal del puerto.
4. **Drops con semánticas distintas**: IOS los separa en `Input queue ... drops` + `Total output drops`; NX-OS usa `input discard`/`output discard`; Huawei usa `Discard` en bloques Input/Output; Fortinet `rx_dropped`/`tx_dropped`; AOS-CX `dropped`.
5. **lastFlap heterogéneo**: `6week(s) 4day(s)` (NX-OS), `2d07h` (NX-OS), timestamp absoluto (Huawei `Last physical up time`), frase completa (AOS-CX `up for 3 weeks (since ...)`), y ausente (IOS clásico) → `null`. Se guarda el texto crudo, sin intentar parsear a fecha.
6. **Contadores > 32 bits**: varios fixtures usan valores > 4.294.967.295 a propósito (NX-OS bytes, Fortinet, MikroTik). Los campos del modelo deben ser `Long`, y los tests fallan si alguien usa `Int`.
7. **Outputs sin métricas**: `get_system_interface` (Fortinet) y `ethernet monitor` (MikroTik) traen estado pero no contadores → todos los campos numéricos `null` + entrada en `warnings`. Comprueba la regla "campo ausente = null, nunca excepción".
8. **Números con formato**: `BW 1000000 Kbit/sec` (multiplicar ×1000), tablas alineadas por columnas de ancho variable (MikroTik), pares `clave: valor,  clave: valor` en una línea (Huawei).
9. **Descripciones con caracteres especiales**: guiones, barras (`TO-SPINE-01-Eth1/12`), espacios (`HUAWEI Test Port - RESERVADO`), y descripción ausente → `null`.

## Cómo usarlos en los tests (patrón sugerido)

```kotlin
@ParameterizedTest
@MethodSource("fixturePairs")  // descubre *.txt con su *.expected.json hermano
fun `parser produce el JSON canonico esperado`(txt: Path, expected: Path) {
    val parser = ParserRegistry.forFixture(txt)   // por carpeta/nombre
    val result = parser.parse(Files.readString(txt))
    assertThat(result).isInstanceOf(ParseResult.Success::class.java)
    JSONAssert.assertEquals(
        Files.readString(expected),
        objectMapper.writeValueAsString(result.toToolOutput()),
        JSONCompareMode.STRICT
    )
}

@Test
fun `ningun parser lanza excepcion ante basura`() {
    val garbage = listOf("", "\u0000\u0001\u0002", "lorem ipsum 12345", "%Error opening ...")
    ParserRegistry.all().forEach { p ->
        garbage.forEach { g -> assertDoesNotThrow { p.parse(g) } }
    }
}
```

Notas:
- Usar `JSONCompareMode.STRICT` para que campos extra o faltantes hagan fallar el test.
- Los `expected.json` siguen exactamente el `outputSchema` de la tool `get_interface_stats` definida en el prompt principal (incluyendo `parsed`, `vendor`, `interfaces`, `warnings` opcional).
- Para ampliar cobertura: agregar fixtures capturados de equipos reales con el mismo par `.txt` + `.expected.json`; el test parametrizado los toma automáticamente. **Sanitizar IPs/seriales reales antes de commitear.**
