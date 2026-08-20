# ADR-0002 — Ejecución de tools independiente del transporte

- Estado: Accepted
- Fecha: 2026-08-20

## Contexto

El dispatcher MCP aplicaba autorización, alcance de operación e invocaba handlers. El chat
interno tenía un camino distinto: extraía bloques de código y escribía comandos directamente
mediante `CommandSink` con pausas temporales. Esto producía semánticas diferentes según la
entrada utilizada.

## Decisión

`ToolExecutor` será el límite de aplicación compartido por MCP, chat interno y futuras
entradas. Es independiente de HTTP y JavaFX, y aplica:

- catálogo de handlers;
- rol y modo read-only;
- ACL por sesión;
- restricciones de `OperationContext`;
- invocación de handlers y errores normalizados.

`McpDispatcher` conserva únicamente responsabilidades del protocolo. Puede efectuar
prevalidaciones para preservar códigos JSON-RPC, pero `ToolExecutor` sigue siendo la barrera
autoritaria. El chat envía bloques aprobables como `propose_commands`; ya no escribe líneas
directamente en la conexión.

## Consecuencias

- Todos los comandos del chat pasan por `ApprovalGate`, snapshots y auditoría existentes.
- El servidor HTTP puede estar apagado y la aplicación continúa usando el catálogo interno.
- Los nuevos transports deben depender de `ToolExecutor`, no de handlers concretos.
- La siguiente evolución podrá mover el límite a un módulo `network-application` sin cambiar
  los adaptadores de entrada.

## Alternativas descartadas

- Hacer que el chat llame por HTTP al servidor local: añade red, autenticación y lifecycle
  innecesarios dentro del mismo proceso.
- Mantener `CommandSink` como camino rápido: duplica políticas y permite divergencias.
