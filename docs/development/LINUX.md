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
