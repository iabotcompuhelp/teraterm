# Evaluación de OpenTermX para operación de red asistida por IA — 2026-08

## Objetivo evaluado

OpenTermX debe permitir que un modelo de lenguaje, desde una consola tipo chat, pueda
observar y operar switches, routers y firewalls para ejecutar monitoreo, diagnóstico,
cambios controlados, respaldos y verificaciones. La plataforma debe poder cambiar de
proveedor o modelo LLM sin perder el contexto de una operación.

Esta evaluación es de solo lectura y corresponde al estado del branch `main` en el
commit `5687ee8` del 12 de junio de 2026. No certifica una release para producción.

## Veredicto ejecutivo

La base técnica es consistente y cubre aproximadamente entre 65 % y 75 % del objetivo.
El monitoreo y los cambios CLI controlados están avanzados. Los principales vacíos son
el respaldo restaurable de configuraciones, el bucle de tools en el chat interno, la
portabilidad completa del contexto entre modelos y la terminación de adaptadores distintos
de CLI.

## Capacidades verificadas en el código

### Servidor MCP

- Transporte JSON-RPC sobre HTTP/SSE embebido en la aplicación.
- Cerca de 40 tools para sesiones, telemetría, inventario, perfiles, operaciones,
  snapshots, políticas, auditoría, macros e integraciones.
- Acceso en proceso a sesiones SSH, Telnet y Serial vivas.
- Integración documentada con clientes MCP externos.

### Monitoreo y diagnóstico

- Ejecución read-only protegida por whitelist por fabricante.
- Runner compartido con mutex por sesión, timeout, limpieza y manejo de paginación.
- Parsers de interfaces, estado de enlaces y utilización.
- Persistencia opcional en PostgreSQL, scheduler y consulta histórica.
- Integraciones read-only con Zabbix y OpManager.
- Fingerprint, inventario, perfiles de dispositivo y topología LLDP/CDP.

### Cambios controlados

- `propose_commands` clasifica el riesgo y exige aprobación humana.
- Contexto de operación con alcance, restricciones y criterios de éxito.
- Snapshots pre/post para cambios CLI cuando hay soporte y base de datos.
- Diff, compliance determinístico, propuesta de rollback y auditoría.
- El output de equipos se considera no confiable y las credenciales se redactan.

### Chat interno

- Proveedores OpenAI, Claude, Gemini, Ollama y LM Studio.
- Contexto de terminal y RAG.
- Extracción de bloques de comandos, revisión humana y ejecución.

El chat interno no funciona todavía como cliente del catálogo de tools. Llama directamente
al proveedor, interpreta bloques de código y escribe líneas en la sesión. Por eso no tiene
el mismo bucle de observación, acción y verificación que un cliente MCP externo, y duplica
parte del camino de ejecución.

## Brechas que impiden completar el objetivo

### 1. Respaldo y restauración no son una capacidad end-to-end

Existen captura de `running-config`, snapshots, almacenamiento de configuraciones, TFTP,
SFTP y macros. No existen tools de dominio explícitas para crear, listar, validar, exportar
y restaurar respaldos. Un snapshot del buffer no es necesariamente una copia completa ni
restaurable.

La plataforma debe distinguir:

- persistir `running-config` en `startup-config` dentro del equipo;
- exportar una copia fuera del equipo;
- conservar una copia íntegra cifrada para restauración;
- conservar una copia redactada para IA, diff y compliance;
- verificar integridad, completitud y posibilidad de restauración.

### 2. Dos caminos de IA distintos

El cliente MCP externo usa handlers y `SessionCommandRunner`; el chat interno usa
`CommandSink` y esperas temporales. Toda operación, sin importar qué modelo o UI la origine,
debe pasar por los mismos casos de uso, políticas, aprobación y auditoría.

### 3. Cambio de LLM sin contrato de contexto portable

Existe `OperationContext`, pero todavía no representa todo lo necesario para retomar una
operación con otro modelo. Faltan un journal estructurado, checkpoints, evidencias,
decisiones, resultados de tools, trabajo pendiente y un resumen de handoff verificable.

La conversación del proveedor no debe ser la fuente de verdad. El estado de la operación
debe residir en OpenTermX y reconstruirse para cualquier LLM.

### 4. Adaptadores incompletos

- CLI: funcional.
- REST read: implementado con alcance limitado por catálogo.
- REST write: propuesta y aprobación implementadas; envío mutativo final es un stub.
- Netmiko, Ansible y SNMP: no disponibles en runtime.
- Snapshot pre/post para cambios vía adaptador: pendiente.

### 5. Autorización y despliegue

- Los roles MCP se autodeclaran mediante header y no están ligados a credenciales.
- La apertura automática de sesiones no cubre todavía un collector read-only preautorizado.
- Falta validar y registrar la matriz real de compatibilidad con clientes MCP.
- Falta el proxy stdio previsto para clientes que no acepten HTTP.

## Estado de calidad y pruebas

El repositorio tiene tests unitarios, de integración, fixtures de red y CI. En esta revisión:

- Gradle reconoció correctamente los módulos y artefactos existentes.
- La recompilación Kotlin avanzó mediante el compilador fallback.
- `pythonTests` no pudo acceder al Python instalado fuera del workspace.
- Los tests que levantan PostgreSQL embebido fallaron por restricciones de conexión del
  entorno de revisión, no por una aserción funcional observada.
- El reporte anterior registra un build completo exitoso y 73 pruebas Python aprobadas;
  debe repetirse en un entorno de CI limpio antes de aceptar una release.

## Riesgos principales

| Prioridad | Riesgo | Impacto |
|---|---|---|
| P0 | El chat interno no usa el mismo motor de tools | Comportamiento y seguridad divergentes |
| P0 | No hay backup restaurable como caso de uso | El objetivo funcional queda incompleto |
| P0 | El contexto depende parcialmente de la conversación LLM | Cambio de modelo pierde continuidad |
| P1 | Roles MCP no autenticados | Separación de funciones solo consultiva |
| P1 | REST write incompleto | Catálogo promete más de lo ejecutable |
| P1 | Validación real de clientes pendiente | Riesgo de incompatibilidad en despliegue |
| P2 | Retención de operaciones/snapshots sin GC | Crecimiento indefinido de almacenamiento |

## Conclusión

No conviene añadir más proveedores o más tools aisladas antes de consolidar el núcleo.
La siguiente etapa debe unificar la ejecución, convertir el contexto en un activo durable
e independiente del modelo y terminar el flujo de backup. El roadmap asociado se encuentra
en [`docs/ROADMAP-AI-OPERATIONS.md`](../ROADMAP-AI-OPERATIONS.md).
