# Ejecutar OpenTermX en Linux

OpenTermX puede compilarse y ejecutarse en Linux con JDK 21. El build principal es Java/Kotlin
y JavaFX descarga automáticamente los binarios correspondientes al sistema operativo.

La plataforma ahora ofrece dos distribuciones distintas:

- `:server`: plano de control MCP headless para una máquina Linux, sin JavaFX;
- `:app`: cliente de escritorio con terminal, consola serial y aprobación humana.

El servidor headless inicial es deliberadamente `readOnly`: conserva operaciones, journals,
handoffs y referencias de evidencia, pero todavía no controla conexiones alojadas en otro
equipo. Esa capacidad se habilitará mediante un agente de borde autenticado, no compartiendo
el registro de sesiones en memoria.

## Requisitos

- distribución Linux x86_64 con entorno gráfico X11 o Wayland;
- JDK 21 completo (incluye `jpackage` si se generará `.deb`/`.rpm`);
- Python 3 con soporte `venv`, necesario para el gate MCP;
- Git;
- opcional: CMake 3.20+, compilador C y headers POSIX para `libopentermx_native.so`;
- opcional: PostgreSQL para telemetría durable en producción.

En Debian/Ubuntu, instala los equivalentes de JDK 21, Python 3/venv, Git y las librerías
GTK/sonido requeridas por JavaFX. En un servidor sin escritorio usa Xvfb únicamente para
tests; la aplicación interactiva necesita una sesión gráfica o forwarding X11.

## Clonar, validar y ejecutar

```bash
git clone https://github.com/iabotcompuhelp/teraterm.git opentermx
cd opentermx
chmod +x gradlew
./gradlew check
./gradlew :app:run
```

## Servidor headless (plano de control)

Para probarlo localmente, únicamente en loopback:

```bash
./gradlew :server:installDist
OPENTERMX_DATA_DIR="$PWD/.local-data" ./server/build/install/opentermx-server/bin/opentermx-server
curl http://127.0.0.1:8765/mcp/health
```

Variables admitidas:

| Variable | Default | Uso |
|---|---:|---|
| `OPENTERMX_BIND` | `127.0.0.1` | Dirección de escucha |
| `OPENTERMX_PORT` | `8765` | Puerto MCP HTTP/SSE |
| `OPENTERMX_DATA_DIR` | `~/.opentermx` | Operaciones, handoffs y snapshots |
| `OPENTERMX_MCP_TOKEN` | vacío | Bearer token; obligatorio fuera de loopback |
| `OPENTERMX_AGENT_PORT` | `8766` | Puerto para heartbeats de agentes de borde |
| `OPENTERMX_AGENT_TOKEN` | vacío | Habilita y autentica el gateway de agentes |
| `OPENTERMX_AGENT_CREDENTIALS_FILE` | vacío | Archivo `agentId=token`; recomendado para revocación individual |
| `OPENTERMX_READ_ONLY` | `true` | Bloquea tools mutativas; cambiar solo tras validar el laboratorio |
| `OPENTERMX_DB_HOST` | vacío | Habilita PostgreSQL central; el agente Windows no usa esta variable |
| `OPENTERMX_DB_PORT` | `5432` | Puerto privado de PostgreSQL |
| `OPENTERMX_DB_NAME` | `opentermx` | Base de datos |
| `OPENTERMX_DB_USER` | `opentermx` | Rol de servicio |
| `OPENTERMX_DB_PASSWORD_FILE` | vacío | Archivo secreto; obligatorio cuando se configura el host |
| `OPENTERMX_DB_REQUIRED` | `true` | Impide arrancar sin persistencia cuando PostgreSQL está habilitado |

Los tokens también admiten `OPENTERMX_MCP_TOKEN_FILE` y `OPENTERMX_AGENT_TOKEN_FILE`; la variante
`*_FILE` tiene precedencia y evita guardar secretos en variables o argumentos. Para el despliegue
recomendado con PostgreSQL privado y secretos montados, consulte `deploy/docker/README.md`.

Para instalarlo como servicio:

1. copia `server/build/install/opentermx-server/` a `/opt/opentermx-server/`;
2. crea el usuario de sistema `opentermx` y `/var/lib/opentermx` con permisos para ese usuario;
3. copia `deploy/linux/opentermx-server.env.example` a `/etc/opentermx/server.env`, configura
   un token aleatorio y aplica permisos `0640`;
4. copia `deploy/linux/opentermx-server.service` a `/etc/systemd/system/`;
5. ejecuta `sudo systemctl daemon-reload && sudo systemctl enable --now opentermx-server`;
6. valida con `systemctl status opentermx-server` y `curl` al endpoint `/mcp/health`.

No expongas el puerto directamente a Internet. Para acceso remoto usa firewall y TLS en un
reverse proxy o VPN; el bearer token es una defensa necesaria, no reemplaza el cifrado.

## Conectar un cliente Windows

El cliente de borde viene incluido en la aplicación de escritorio. No escucha conexiones
entrantes: publica cada cinco segundos las sesiones activas del `SessionRegistry` hacia el
gateway Linux. Esto cubre automáticamente sesiones SSH y conexiones seriales/consola abiertas
en OpenTermX. Las últimas líneas se redactan en Windows antes de abandonar el equipo.

Configura las variables antes de iniciar OpenTermX:

```powershell
$env:OPENTERMX_CONTROL_PLANE_URL = "https://opentermx.example.com:8766"
$env:OPENTERMX_AGENT_TOKEN = "el-mismo-token-configurado-en-el-servidor"
$env:OPENTERMX_AGENT_ID = "noc-win-01"
$env:OPENTERMX_AGENT_NAME = "Consola NOC principal"
./app/build/install/app/bin/app.bat
```

Para una prueba aislada dentro de una LAN puede utilizarse `http://IP-LINUX:8766`, pero en
producción el gateway debe publicarse mediante HTTPS o VPN. Cada PC necesita un `AGENT_ID`
único. Si el agente deja de reportar durante 20 segundos, sus sesiones desaparecen del catálogo.

En el MCP central, `list_sessions` devuelve las sesiones remotas como
`<agentId>:<sessionId>` y `inspect_session` permite consultar su buffer redactado. Este primer
contrato no transporta credenciales.

El protocolo de tareas remotas también está implementado para pruebas de laboratorio: la cola
vive en `OPENTERMX_DATA_DIR/tasks`, persiste cada tarea y resultado de forma atómica, rechaza la
reutilización de un `taskId` con contenido diferente y entrega tareas únicamente al `agentId`
destino. El agente consulta la cola después de cada heartbeat y reporta el resultado final.

En Windows, toda tarea pasa por el mismo clasificador de riesgo y diálogo JavaFX usado por las
operaciones MCP locales. Un rechazo no llega al `CommandSink`; si el operador edita la propuesta,
solo las líneas finalmente aprobadas se envían a la sesión SSH o serial. Las tareas expiradas o
dirigidas a una sesión cerrada fallan sin ejecutar comandos.

El servidor publica tres tools MCP para este flujo:

- `propose_remote_commands`: crea una tarea firmada para una sesión federada;
- `get_remote_task`: consulta estado y resultado redactado;
- `cancel_remote_task`: cancela una tarea pendiente o entregada.

Las tres exigen una operación activa y la tarea queda ligada a su `operationId`. El scope de la
operación restringe dispositivos y comandos. Proponer y cancelar son mutativas, por lo que el
default `OPENTERMX_READ_ONLY=true` las bloquea. Para una prueba controlada se puede configurar
`OPENTERMX_READ_ONLY=false`; esto exige además un `OPENTERMX_AGENT_TOKEN` de al menos 16 bytes.

Cada payload se firma con HMAC-SHA256 usando el secreto del agente. Los leases vencidos se
reentregan con la misma firma y un contador de intento. Windows persiste el resultado antes de
reportarlo en `~/.opentermx/agent/completed-tasks.log`: tras una caída reenvía el resultado sin
ejecutar nuevamente los comandos. La creación, resultado o rechazo quedan en el journal durable
de la operación para formar parte del handoff.

### Estado del agente en Windows

En OpenTermX abre **Control → Estado del agente** o pulsa el badge `Agent` de la barra inferior.
La pantalla muestra identidad, gateway, estado, último heartbeat, sesiones publicadas, tarea en
curso, último error e historial de hasta 50 resultados. El botón de desconexión detiene el
scheduler sin cerrar las sesiones SSH/serial; **Reconectar** crea un cliente nuevo y conserva la
idempotencia e historial desde `completed-tasks.log`.

Estados posibles:

- `STARTING`: configurado y esperando el primer heartbeat;
- `CONNECTED`: el último heartbeat fue aceptado;
- `DEGRADED`: falló red, autenticación, firma o reporte; el detalle aparece en último error;
- `STOPPED`: desconectado manualmente y disponible para reconexión;
- `DISABLED`: no existe `OPENTERMX_CONTROL_PLANE_URL` en el entorno de arranque.

### Configuración visual en Windows

Abre **Control → Configurar agente…** para administrar la conexión sin variables de entorno.
Se pueden cambiar URL, identificador único, nombre visible, intervalo de heartbeat, directorio de
estado y token. **Probar conexión** consulta `/agent/v1/health`; **Guardar y aplicar** reconstruye
el cliente en caliente sin cerrar las terminales existentes.

Una configuración guardada desde la UI prevalece sobre el entorno. En instalaciones anteriores,
`enabled=null` mantiene compatibilidad y continúa leyendo las variables `OPENTERMX_*`; al guardar
por primera vez, la decisión activar/desactivar pasa a ser explícita.

En Windows, el token se guarda en **Windows Credential Manager** para el usuario actual con la
referencia `OpenTermX/edge-agent/<agentId>`; `settings.json` conserva solamente esa referencia.
Los tokens antiguos cifrados con AES-256-GCM se migran automáticamente al iniciar y se eliminan
del JSON después de confirmar la escritura. En plataformas sin Credential Manager, o si este no
está disponible, `SecretCipher` se mantiene como respaldo cifrado. La opción para eliminar el
token también borra la credencial nativa. El valor nunca se escribe en logs ni se envía como
argumento de un proceso.

Para crear una distribución portable con scripts y todas las dependencias:

```bash
./gradlew :app:installDist
./app/build/install/app/bin/app
```

GitHub Actions ejecuta tests sobre Ubuntu y publica el artefacto
`opentermx-linux-portable`, generado desde `app/build/install/app/`, y
`opentermx-server-linux`, generado desde `server/build/install/opentermx-server/`.

## Instalador Linux

Desde Linux, con JDK 21 completo:

```bash
./gradlew :app:jpackageLinuxDeb   # requiere las herramientas Debian
./gradlew :app:jpackageLinuxRpm   # requiere rpmbuild
```

Los resultados quedan en `app/build/jpackage-deb/` o `app/build/jpackage-rpm/`.

## Datos que deben migrarse

Copia el directorio de usuario de OpenTermX desde Windows al home Linux:

```text
Windows: C:\Users\<usuario>\.opentermx\
Linux:   /home/<usuario>/.opentermx/
```

Antes de copiar:

1. cierra OpenTermX en ambos equipos;
2. conserva una copia de seguridad del directorio original;
3. revisa rutas absolutas guardadas (keys SSH, logs, KB, certificados y directorios TFTP);
4. asigna permisos restrictivos: directorios `700`, secretos y keys `600`;
5. vuelve a validar las API keys cifradas. Si el cifrado depende de identidad/host, reingrésalas
   mediante Setup → AI Assistant;
6. no copies caches Gradle, `build/` ni entornos virtuales Python.

Las operaciones, journals, handoffs y snapshots viven bajo `.opentermx/operations/`, por lo
que pueden viajar junto con el resto del estado y continuar con otro proveedor LLM.

## Diferencias conocidas

- RDP usa `mstsc.exe` y Windows Credential Manager; permanece deshabilitado en Linux.
- Serial funciona por defecto con jSerialComm. El usuario Linux debe pertenecer al grupo que
  tenga acceso a `/dev/ttyUSB*`/`/dev/ttyACM*` y volver a iniciar sesión tras cambiar grupos.
- El backend nativo es opcional. Compílalo con CMake y expón `libopentermx_native.so` mediante
  `java.library.path` o el loader del sistema. Si no está disponible, OpenTermX vuelve al
  terminal Kotlin y jSerialComm.
- El wrapper MCP stdio se genera como `~/.opentermx/bin/opentermx-mcp-stdio` y debe conservar
  permiso de ejecución.
- Los paths guardados con `C:\...` deben sustituirse por paths Linux antes de usar KB, keys,
  TLS, logs o transferencias.

## Validación recomendada en el nuevo host

1. `./gradlew check` sin servicios externos.
2. `./gradlew :app:installDist` y arranque desde el script generado.
3. Crear una conexión SSH read-only a un equipo de laboratorio.
4. Iniciar el MCP en loopback, ejecutar `ping`, `tools/list` y `list_sessions`.
5. Iniciar una operación, crear snapshot y exportar un handoff.
6. Cambiar a un proveedor local o cloud desde **Cambiar modelo** y confirmar que el preview
   contiene journal y referencias SHA-256, pero no configuraciones completas ni secretos.
7. Probar escritura únicamente en laboratorio y con aprobación humana.
