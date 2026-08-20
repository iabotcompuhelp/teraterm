# Roadmap de OpenTermX para operaciones de red asistidas por IA

## Propósito

Este documento ordena el desarrollo de OpenTermX para construir una plataforma segura,
escalable y portable entre proveedores LLM. Es un roadmap vivo: cada hito debe terminar
con código, pruebas, documentación y evidencia de aceptación.

La evaluación de partida está en
[`docs/review/REVIEW-AI-MCP-OBJECTIVE-2026-08.md`](review/REVIEW-AI-MCP-OBJECTIVE-2026-08.md).

## Principios no negociables

1. **El estado vive en OpenTermX, no en el LLM.** Una conversación puede desaparecer o
   cambiar de proveedor sin perder el estado verificable del trabajo.
2. **Una sola vía de ejecución.** Chat interno, MCP, REST y automatizaciones llaman a los
   mismos casos de uso y al mismo `SessionCommandRunner`.
3. **Los modelos proponen; las políticas autorizan.** El LLM nunca decide sus propios
   permisos ni accede a credenciales.
4. **Toda mutación es observable y reversible cuando sea posible.** Aprobación, evidencia
   pre/post, auditoría y plan de recuperación forman una unidad.
5. **Datos de red son no confiables.** Outputs, banners, RAG y APIs externas nunca se
   interpretan como instrucciones privilegiadas.
6. **Contratos antes que proveedores.** El dominio no importa SDKs de OpenAI, Anthropic,
   Google, Ollama ni MCP.
7. **Capacidades declaradas equivalen a capacidades comprobadas.** Una feature apagada,
   incompleta o sin test end-to-end no se anuncia como disponible.

## Arquitectura objetivo

```text
JavaFX Chat     Cliente MCP     REST/CLI futura     Scheduler
      \             |                |                 /
       +------------+----------------+----------------+
                            |
                    Application Services
        OperationService / ToolExecutor / BackupService
        MonitoringService / ApprovalService / AuditService
                            |
        Policy + AuthZ + SessionCommandRunner + Adapters
                            |
           SSH / Telnet / Serial / REST / NETCONF / etc.

LLM Provider Gateway
  OpenAI | Claude | Gemini | Ollama | LM Studio | futuro
          |
  Conversation Orchestrator ---- Context Store
                                 Operation journal
                                 Evidence/checkpoints
                                 Portable handoff package
```

### Límites de módulos recomendados

- `common`: tipos estables, IDs, errores y contratos sin dependencias de infraestructura.
- `network-application` (nuevo): casos de uso y puertos; no conoce JavaFX, Javalin ni SDKs
  LLM.
- `network-execution` o evolución de `mcp-server`: runner, adapters y ejecución concreta.
- `operation-store` o evolución de `telemetry-db`: contexto, journal, checkpoints y
  evidencias.
- `llm-gateway` o evolución de `ai-assistant`: interfaz uniforme de modelos y adaptación
  de mensajes/tools.
- `agent-orchestrator` (nuevo): loop de tool calling, presupuesto, cancelación, handoff y
  recuperación.
- `mcp-server`: adaptador de entrada MCP; transforma requests en casos de uso.
- `app`: UI y composición de dependencias; sin lógica operativa duplicada.

No es necesario crear todos los módulos al inicio. Primero se extraen interfaces y tests;
los movimientos físicos se hacen cuando reduzcan acoplamiento de manera medible.

## Portabilidad de modelos y contexto

### Contrato del proveedor

Crear una interfaz independiente de proveedor, por ejemplo:

```kotlin
interface LlmGateway {
    val capabilities: LlmCapabilities
    suspend fun complete(request: ModelRequest): ModelResponse
    suspend fun stream(request: ModelRequest): Flow<ModelEvent>
}
```

`ModelRequest` debe usar tipos propios de OpenTermX:

- mensajes normalizados;
- definición normalizada de tools;
- política de tool choice;
- límites de tokens y tiempo;
- identificador de operación y correlación;
- attachments referenciados, no secretos embebidos.

Cada adapter traduce esos tipos al API del proveedor. El orquestador debe poder elegir
proveedor por configuración, disponibilidad y capacidades, sin `when(provider)` fuera del
gateway.

### Contexto durable

Separar cuatro niveles:

1. **Contexto de sesión LLM:** mensajes recientes; prescindible y compactable.
2. **Contexto de operación:** objetivo, alcance, restricciones, criterios y estado.
3. **Journal de trabajo:** decisiones, tool calls, resultados, aprobaciones y errores.
4. **Evidencia:** snapshots, métricas, diffs, archivos y hashes verificables.

El modelo recibe una vista derivada; nunca es dueño de la fuente original.

### Paquete de handoff

Definir un schema versionado `operation-handoff.schema.json` con:

- `schemaVersion`, `operationId`, timestamps y estado;
- objetivo y alcance autorizado;
- dispositivos y perfiles relevantes;
- restricciones y criterios de éxito;
- resumen factual generado y fecha del resumen;
- decisiones confirmadas por operador;
- tools ejecutadas con argumentos redactados y resultados referenciados;
- evidencias por ID/hash, no blobs gigantes;
- cambios aplicados y estado de verificación;
- errores abiertos, riesgos y próximos pasos;
- presupuesto restante y causa del cambio de modelo.

El resumen producido por un LLM es no confiable hasta contrastarlo con journal y evidencia.
Al cambiar de modelo, OpenTermX construye un nuevo prompt desde el paquete y obliga al nuevo
modelo a confirmar objetivo, restricciones y estado antes de continuar.

### Estrategia de cambio y fallback

- Selección manual siempre disponible.
- Fallback automático solo para fallos transitorios y antes de cualquier mutación.
- Nunca repetir automáticamente una tool mutativa cuyo resultado sea incierto.
- Usar claves de idempotencia y estados `NOT_STARTED`, `IN_PROGRESS`, `SUCCEEDED`,
  `FAILED` y `UNKNOWN`.
- Circuit breaker por proveedor y métricas de latencia/error.
- Matriz de capacidades: tools, streaming, tamaño de contexto, JSON estructurado y visión.
- Política configurable de datos: modelos cloud, modelos locales y clasificación de la
  información permitida para cada uno.

## Plan priorizado

### Estado de ejecución

Actualizado el 20 de agosto de 2026:

- [x] Separar en CI la suite JVM multiplataforma y el contrato MCP black-box.
- [x] Publicar reportes de ambas capas aunque una prueba falle.
- [x] Detectar y recrear entornos Python rotos por cambio del intérprete base.
- [x] Documentar la estrategia de pruebas.
- [x] Adoptar ADRs y registrar que el estado operativo pertenece a OpenTermX.
- [ ] Confirmar la primera ejecución limpia de los nuevos jobs en GitHub Actions.
- [x] Actualizar documentación y declarar el snapshot de tools como fuente verificable.
- [x] Definir y probar reglas automáticas de dependencias entre módulos.
- [x] Extraer `ToolExecutor` como límite independiente del transporte.
- [x] Migrar MCP y chat interno al mismo ejecutor y retirar la escritura directa del chat.
- [x] Añadir pruebas contractuales de entrada interna y MCP.
- [x] Implementar journal append-only de operaciones con correlación y redacción.
- [x] Crear schema `operation-handoff` 1.0 y export determinista.
- [x] Recuperar y reasociar operaciones abiertas después de reinicio/cambio de cliente.
- [x] Integrar referencias verificables de snapshots al handoff sin copiar contenido.
- [ ] Integrar decisiones confirmadas y estados mutativos `UNKNOWN` al handoff.
- [x] Añadir UI para previsualizar, confirmar y ejecutar el cambio de modelo.
- [ ] Validar handoff end-to-end entre proveedores reales y fallo durante tool call.

Estado de hitos:

- **Hito 0: implementación terminada.** La confirmación del runner remoto ocurre en el
  primer push/PR y es un gate de integración, no trabajo de código pendiente.
- **Hito 1: terminado.** El criterio contractual chat/MCP está cubierto por tests y la
  aplicación ya no usa `CommandSink` para ejecutar respuestas del modelo.

### Hito 0 — Baseline reproducible y gobierno técnico (P0)

Objetivo: poder cambiar código con confianza.

- Corregir el entorno CI para ejecutar `gradlew check` desde cero en Windows y Linux.
- Separar tests rápidos, integración PostgreSQL y pruebas end-to-end de clientes.
- Publicar conteo y resultado de tests como artefacto CI.
- Actualizar documentación y catálogo para eliminar estados contradictorios.
- Adoptar ADRs para decisiones arquitectónicas y un formato único de issues/hitos.
- Añadir reglas de dependencias entre módulos para impedir ciclos.

Criterio de salida: checkout limpio + JDK 21 + Python soportado ejecuta toda la suite con
un comando, y el resultado se reproduce en CI.

### Hito 1 — Núcleo único de operaciones (P0)

Objetivo: eliminar caminos divergentes.

- Extraer `ToolExecutor`/casos de uso independientes de MCP.
- Hacer que handlers MCP sean adaptadores delgados.
- Migrar el chat interno a los mismos casos de uso.
- Retirar el envío directo con pausas fijas desde `AiChatPanel`.
- Centralizar autorización, redacción, aprobación, timeout y auditoría.
- Añadir IDs de correlación desde UI/cliente hasta equipo y evidencia.

Criterio de salida: la misma prueba contractual ejecutada por chat interno y MCP produce
las mismas decisiones, auditoría y resultados normalizados.

### Hito 2 — Context Store y handoff entre modelos (P0)

Objetivo: cambiar de modelo sin perder el trabajo.

- Evolucionar `OperationContext` a una máquina de estados durable.
- Implementar journal append-only y referencias de evidencia.
- Crear schema y serializer del paquete de handoff.
- Implementar compactación determinística de historial.
- Añadir UI: cambiar modelo, previsualizar handoff y confirmar continuación.
- Añadir recovery después de reiniciar la aplicación.
- Probar handoff OpenAI → Ollama, Claude → OpenAI y fallo durante tool call.

Estado: **en desarrollo**. Persistencia, journal, redacción, correlación, schema/versionado,
export determinista, recuperación/rebind, evidencias snapshot por hash y UI de
preview/confirmación están implementados. Las decisiones estructuradas, el estado mutativo
incierto y la matriz end-to-end de proveedores siguen pendientes.

Criterio de salida: una operación de diagnóstico puede comenzar con un proveedor, continuar
con otro y conservar alcance, decisiones, evidencias y pendientes sin copiar manualmente el
chat.

### Hito 3 — Backup completo y verificable (P0)

Objetivo: completar el caso de uso más importante que falta.

- Implementar `BackupService` y tools `backup_device_config`, `list_device_backups`,
  `verify_device_backup`, `compare_device_backup` y `propose_restore_backup`.
- Capturar desde el equipo mediante un adapter, no desde el buffer visual.
- Guardar copia íntegra cifrada y copia redactada con hashes relacionados.
- Soportar CLI, SFTP y TFTP según perfil del fabricante.
- Añadir retención, exportación, integridad y metadatos.
- Restauración solo como propuesta aprobada, con snapshot pre/post y verificación.

Criterio de salida: backup y restauración se demuestran end-to-end en al menos Cisco IOS,
Aruba/HPE y un firewall soportado, incluyendo recuperación fallida segura.

### Hito 4 — Seguridad de identidad y autorización (P1)

Objetivo: pasar de seguridad local consultiva a control verificable.

- Tokens separados por cliente con hash, expiración y revocación.
- Roles y ACL asociados al token; retirar rol auto-declarado como autoridad.
- Scopes por device, grupo, tool y tipo read/write.
- TLS obligatorio fuera de loopback.
- Sesiones de collector read-only preautorizadas y diferenciadas de sesiones interactivas.
- Registro durable y tamper-evident de operaciones críticas.

Criterio de salida: un cliente no puede elevar rol, ampliar devices ni ejecutar una tool
fuera de sus scopes alterando headers o argumentos.

### Hito 5 — Orquestador agéntico y resiliencia LLM (P1)

Objetivo: hacer confiable el loop observar → decidir → actuar → verificar.

- Implementar loop de tools con límites de pasos, tiempo y costo.
- Cancelación, retry selectivo, circuit breaker y fallback de proveedor.
- Idempotencia para mutaciones y manejo explícito de resultado desconocido.
- Verificación obligatoria después de cambios.
- Política de escalamiento humano ante ambigüedad, riesgo o pérdida de conectividad.
- Evaluaciones offline con escenarios y respuestas grabadas.

Criterio de salida: fallos simulados de proveedor, red y dispositivo no duplican cambios ni
corrompen el estado de operación.

### Hito 6 — Adaptadores y escala (P2)

Objetivo: ampliar cobertura sin comprometer el núcleo.

- Completar REST write y sus snapshots.
- Priorizar NETCONF/RESTCONF o Ansible según equipos objetivo antes de Netmiko genérico.
- Concurrencia acotada, colas por dispositivo y backpressure.
- Workers separados opcionales para collectors y operaciones largas.
- Métricas OpenTelemetry, health checks y panel operacional.
- Políticas de retención y GC de operations, snapshots y evidencias.

Criterio de salida: pruebas de carga documentan límites de sesiones, devices, tool calls y
almacenamiento, con degradación controlada.

### Hito 7 — Release piloto (P2)

Objetivo: validar el producto con usuarios y equipos reales.

- Matriz real Claude Desktop/Cursor/Cline/Continue y chat interno.
- Pruebas con laboratorio multi-vendor y fallos inducidos.
- Instaladores firmados, migraciones y rollback de versión.
- Guía operativa, threat model y runbooks de incidentes.
- Piloto read-only; después backup; por último cambios controlados.

Criterio de salida: piloto aprobado con evidencia, métricas y lista explícita de riesgos
aceptados.

## Orden de ejecución inmediato

Las primeras entregas deben ser pequeñas y verticales:

1. Baseline CI y actualización documental.
2. Contrato `ToolExecutor` con una tool read-only migrada.
3. Chat interno ejecutando esa tool mediante el nuevo contrato.
4. Journal mínimo y handoff exportable/importable.
5. Cambio de proveedor conservando una operación read-only.
6. Backup Cisco IOS íntegro + redactado + verificación.
7. Generalización del backup mediante adapters.

No se inicia REST write, Ansible o automatización masiva hasta terminar los pasos 1–5.

## Definición de terminado

Una funcionalidad está terminada únicamente si incluye:

- contrato y límites documentados;
- implementación sin bypass de seguridad;
- unit tests y pruebas de integración;
- escenario end-to-end cuando toca red, modelo o almacenamiento;
- auditoría y observabilidad;
- migración y compatibilidad hacia atrás cuando aplique;
- documentación de operador;
- criterio de rollback;
- actualización del catálogo de capacidades.

## Seguimiento

Cada hito debe tener un issue épico y entregas verticales pequeñas. El estado recomendado
es `Propuesto → Diseñado → En desarrollo → Verificado → Piloto → Estable`. El porcentaje
de avance debe calcularse por criterios de aceptación cumplidos, no por líneas de código.
