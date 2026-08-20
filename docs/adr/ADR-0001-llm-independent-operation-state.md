# ADR-0001 — Estado de operación independiente del proveedor LLM

- Estado: Accepted
- Fecha: 2026-08-20

## Contexto

OpenTermX soporta varios proveedores LLM y debe poder cambiar de modelo durante una
operación. El historial privado de un proveedor no es portable, puede truncarse y no es
evidencia suficiente de lo ocurrido sobre un equipo.

El proyecto ya dispone de `OperationContext`, snapshots y auditoría, pero no de un contrato
único que permita reconstruir el trabajo para otro modelo.

## Decisión

OpenTermX será la fuente de verdad de cada operación. Mantendrá por separado:

1. contexto y alcance autorizado;
2. journal de tool calls, decisiones y aprobaciones;
3. evidencias referenciadas mediante IDs y hashes;
4. mensajes recientes del modelo, que serán compactables y prescindibles;
5. un paquete de handoff versionado derivado de los elementos anteriores.

Los adaptadores de proveedores solo traducirán mensajes y tools. No almacenarán estado de
dominio ni decidirán qué información constituye el contexto de una operación.

## Consecuencias

- Cambiar de proveedor no exige copiar manualmente la conversación.
- El contexto puede reconstruirse después de reiniciar la aplicación.
- Se necesita persistencia y migración de schemas para journal y handoff.
- Los resúmenes producidos por modelos se consideran datos derivados y deben contrastarse
  con el journal y las evidencias.
- El orquestador deberá manejar idempotencia y el estado `UNKNOWN` de acciones mutativas.

## Alternativas descartadas

- Usar exclusivamente el historial del proveedor: no es portable ni verificable.
- Guardar un único prompt acumulativo: escala mal y mezcla evidencia con interpretación.
- Copiar conversaciones completas entre proveedores: puede exceder contexto, filtrar datos
  y conservar afirmaciones incorrectas sin procedencia.
