# Journal durable y handoff de operaciones

OpenTermX conserva el estado operativo fuera de la conversación del proveedor LLM. Cada
operación abierta se almacena en `~/.opentermx/operations/<operationId>/`:

- `context.json`: objetivo, alcance, restricciones y criterios de éxito;
- `journal.jsonl`: eventos append-only ordenados por `sequence`;
- `handoffs/handoff-<timestamp>.json`: paquetes portables generados;
- `closed.json`: resumen de cierre, cuando corresponda.

## Journal

`ToolExecutor` registra `TOOL_STARTED`, `TOOL_SUCCEEDED` y `TOOL_REJECTED` con un
`correlationId` común. También se registran inicio, recuperación y cierre de la operación.
Antes de escribir se redactan claves que representen passwords, secretos, tokens, API keys o
credenciales, y se acotan strings y colecciones grandes.

El journal es evidencia de coordinación, no un almacén de outputs completos. Archivos,
snapshots y configuraciones deben referenciarse por ID/hash en lugar de copiarse al handoff.

## Paquete de handoff

El contrato formal está en
[`operation-handoff.schema.json`](../mcp-server/src/main/resources/schemas/operation-handoff.schema.json).
La versión inicial es `1.0` e incluye contexto, journal redactado, resumen determinista,
riesgos abiertos, próximos pasos y metadatos del cambio de proveedor.

El resumen no lo genera un modelo: se deriva de eventos persistidos. Por ello, dos exports
del mismo estado y timestamp producen el mismo paquete.

Los snapshots asociados a la operación se incorporan como referencias de evidencia con
`id`, tipo, SHA-256, timestamp, sesión y dispositivo. El contenido de configuración no se
copia al paquete; el hash permite comprobar posteriormente que la evidencia recuperada es
la misma que existía al crear el handoff.

## Flujo de cambio de modelo

1. El cliente actual llama `export_operation_handoff`, indicando opcionalmente proveedor
   origen/destino, motivo y presupuesto restante.
2. OpenTermX persiste y devuelve el paquete.
3. El nuevo cliente llama `resume_operation(operationId)`.
4. OpenTermX reasocia la operación a la nueva sesión y exige confirmación de objetivo,
   alcance y restricciones antes de continuar.

En el chat JavaFX, **Cambiar modelo** abre este flujo como diálogo de dos pasos. Primero el
operador elige proveedor/modelo y genera una vista previa de solo lectura. El botón de
confirmación permanece deshabilitado hasta que el preview exista. Al confirmar:

- se valida que el proveedor tenga credenciales o endpoint configurado;
- se persiste la nueva selección y se invalida la verificación del proveedor anterior;
- se inicia un historial de chat nuevo;
- el paquete se inyecta en el system prompt del proveedor nuevo.

`resume_operation` no repite ninguna tool previa. Una mutación cuyo resultado sea incierto
debe permanecer como riesgo abierto hasta que una verificación determine su estado.

## Alcance actual

Esta entrega cubre journal, recuperación después de reinicio, export/rebind, referencias de
snapshots y preview/confirmación JavaFX. Quedan para las siguientes entregas del Hito 2 las
decisiones humanas estructuradas, manejo explícito de una tool mutativa en estado `UNKNOWN`
y pruebas end-to-end con proveedores reales.
