# Estrategia de pruebas

## Capas

### JVM

```bash
./gradlew test
```

Ejecuta las pruebas Java/Kotlin/Groovy de todos los módulos. CI las corre en Linux y
Windows. En Linux, las pruebas JavaFX se ejecutan bajo Xvfb.

### Contrato MCP black-box

```bash
./gradlew :mcp-server:pythonTests
```

Crea un venv bajo `mcp-server/build/`, levanta el servidor de prueba y ejecuta pytest/httpx
como un cliente MCP real. Puede fijarse el intérprete con `MCP_TEST_PYTHON`.

El build valida que el Python del venv todavía sea ejecutable. Si el Python base fue
movido o desinstalado, el venv se recrea automáticamente.

### Gate completo

```bash
./gradlew check
```

Ejecuta las pruebas JVM y el contrato MCP. Es el gate previo a release.

## Límites entre módulos

```bash
./gradlew verifyModuleBoundaries
```

Falla ante ciclos, módulos inexistentes, dependencias hacia `:app` desde capas inferiores
o dependencias de proyecto agregadas a `:common`. También forma parte de `check`.

## Interpretación de fallos

- Fallo en `jvm-tests`: código de módulo, integración interna o comportamiento específico
  del sistema operativo.
- Fallo en `mcp-contract-tests`: protocolo, schemas, autenticación o comportamiento HTTP.
- Fallo al crear PostgreSQL embebido: revisar primero restricciones de procesos, sockets y
  archivos temporales del runner antes de clasificarlo como fallo funcional.

## Reglas para nuevas funcionalidades

- Lógica pura: unit test.
- Persistencia o adapter: integration test.
- Tool MCP: test contractual del handler y, si cambia el wire, test Python black-box.
- UI: probar el presenter/controlador sin toolkit cuando sea posible; reservar JavaFX para
  interacción que realmente dependa de nodos.
- Mutaciones de red: incluir éxito, rechazo humano, timeout y resultado incierto.
