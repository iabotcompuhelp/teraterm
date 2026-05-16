# Prompt para Claude Code — Emulador de Terminal tipo Tera Term

## Prompt Principal (copia esto en Claude Code)

```
Quiero que me ayudes a crear una aplicación de escritorio multiplataforma que emule las funcionalidades principales de Tera Term (emulador de terminal). El proyecto se llamará "OpenTermX" y se desarrollará en IntelliJ IDEA usando una arquitectura modular con lenguajes JVM combinados.

## Arquitectura General

Diseña el proyecto con la siguiente estructura de módulos y lenguajes:

### Módulo 1: UI/Frontend — Kotlin + JavaFX
- Ventana principal con barra de menú (File, Edit, Setup, Control, Window, Help)
- Sistema de múltiples pestañas para sesiones simultáneas
- Panel de terminal con emulación VT100/VT220/xterm (renderizado de texto con soporte ANSI escape codes, colores 256 y truecolor)
- Diálogo de configuración de conexión (Serial, SSH, Telnet, TCP Raw)
- Panel lateral de sesiones activas
- Barra de estado con información de conexión (baudrate, protocolo, estado)
- Soporte para temas claro/oscuro
- Atajos de teclado configurables
- Fuente monoespaciada configurable (tamaño, familia)

### Módulo 2: Comunicación Serial — Java
- Conexión RS-232 usando la librería jSerialComm
- Configuración completa de puerto: baudrate (300-921600), data bits (5-8), stop bits (1, 1.5, 2), paridad (None, Odd, Even, Mark, Space)
- Control de flujo: Hardware (RTS/CTS), Software (XON/XOFF), None
- Detección automática de puertos COM/ttyUSB disponibles
- Monitoreo de señales DTR, RTS, CTS, DSR, DCD, RI
- Envío de BREAK signal
- Buffer circular configurable para historial de datos recibidos
- Logging de datos raw (hex y ASCII simultáneo)
- **Backend serial nativo opcional**: librería C `opentermx_native` (`native/src/serial_native.{c,h}`, build CMake) expuesta a Java vía JNA bajo `serial-comm/.../nativeio/`. La DLL/so/dylib se empaca en `serial-comm/src/main/resources/<platform>/` y JNA la carga desde el classpath. Interfaz común `SerialPortConnection` para que `SerialConnection` (jSerialComm) y `NativeSerialConnection` sean drop-in. Selección del backend en runtime con esta precedencia: (1) system property `-Dopentermx.serial.backend=native|jserialcomm`; (2) `AdditionalSettings.serialBackend` (Setup → Additional → tab Serial). Fallback silencioso a jSerialComm con warning si la DLL no carga. El módulo nativo también expone un emulador VT (`NativeTerminal`, `opentermx_term_*`) disponible pero no integrado en `TerminalView`.

### Módulo 3: Comunicación SSH — Java
- Cliente SSH2 usando Apache MINA SSHD o JSch
- Autenticación por contraseña y por clave pública (RSA, ED25519, ECDSA)
- Port forwarding (local y remoto)
- SFTP integrado para transferencia de archivos
- Gestión de known_hosts
- Soporte para SSH tunneling
- Reenvío de agente SSH (agent forwarding)
- Keep-alive configurable

### Módulo 4: Comunicación Telnet — Java
- Cliente Telnet usando Apache Commons Net
- Negociación de opciones Telnet estándar
- Soporte para Telnet sobre TLS (STARTTLS)

### Módulo 5: Transferencia de Archivos — Java
- Protocolos: XMODEM, YMODEM, ZMODEM, Kermit
- Barra de progreso visual durante la transferencia
- Verificación de integridad (CRC)
- Cancelación de transferencia en curso

### Módulo 6: Motor de Macros/Scripting — Groovy
- Lenguaje de macros compatible con la sintaxis básica de Tera Term Macro Language (TTL)
- Comandos esenciales: connect, sendln, waitfor, pause, messagebox, inputbox, filelog
- Editor de macros integrado con syntax highlighting
- Ejecución de macros al conectar (auto-login scripts)
- Variables, condicionales (if/else), bucles (for/while)
- Acceso al portapapeles del sistema
- Log de ejecución de macros

### Módulo 7: Logging y Captura — Kotlin
- Log de sesión a archivo (texto plano, HTML con colores, raw binary)
- Timestamp opcional por cada línea recibida
- Rotación automática de logs por tamaño o tiempo
- Captura de pantalla del terminal a imagen PNG
- Exportación del buffer del terminal

## Requisitos Técnicos

- Build system: Gradle con Kotlin DSL (build.gradle.kts)
- Java 21+ (LTS) como versión mínima
- Patrón de diseño: MVVM para la capa UI
- Comunicación entre módulos mediante eventos (Event Bus pattern)
- Manejo de conexiones en coroutines de Kotlin (no bloquear el hilo de UI)
- Configuración persistente en archivo JSON (directorio ~/.opentermx/)
- Internacionalización (i18n) preparada desde el inicio (español e inglés)
- Tests unitarios con JUnit 5 para los módulos de comunicación

## Estructura del Proyecto

```
OpenTermX/
├── build.gradle.kts                 # Configuración raíz
├── settings.gradle.kts
├── app/                             # Módulo principal (Kotlin)
│   └── src/main/kotlin/
├── serial-comm/                     # Comunicación serial (Java)
│   └── src/main/java/
├── ssh-comm/                        # Comunicación SSH (Java)
│   └── src/main/java/
├── telnet-comm/                     # Comunicación Telnet (Java)
│   └── src/main/java/
├── file-transfer/                   # Transferencia de archivos (Java)
│   └── src/main/java/
├── macro-engine/                    # Motor de macros (Groovy)
│   └── src/main/groovy/
├── logger/                          # Logging y captura (Kotlin)
│   └── src/main/kotlin/
├── rest-api/                        # API REST headless (Kotlin + Javalin)
│   └── src/main/kotlin/
├── mcp-server/                      # Servidor MCP (Kotlin + Javalin) — expone sesiones,
│   │                                # KB y propose_commands a clientes externos como
│   │                                # Claude Desktop, Cursor o Claude Code
│   ├── src/main/kotlin/
│   └── src/test/python/             # Tests de integración black-box (pytest + httpx)
└── common/                          # Interfaces compartidas y eventos
    └── src/main/kotlin/
```

## Plan de Desarrollo

Comienza por:
1. Crear la estructura del proyecto Gradle multi-módulo
2. Implementar el módulo "common" con las interfaces y el Event Bus
3. Crear la ventana principal con JavaFX (módulo app) con una terminal básica
4. Implementar la conexión serial como primer protocolo funcional
5. Integrar la terminal con la conexión serial para enviar/recibir datos

Después de cada paso, espera mi confirmación antes de continuar al siguiente. Muéstrame la estructura de archivos y el código generado para revisión.
```

---

## Prompts de Seguimiento (usa estos después del prompt principal)

### Para avanzar con SSH:
```
Ahora implementa el módulo ssh-comm. Necesito conexión SSH2 con autenticación por contraseña y clave pública. Integra con la terminal existente para que pueda abrir una nueva pestaña SSH.
```

### Para el sistema de macros:
```
Implementa el motor de macros en Groovy. Quiero poder escribir scripts simples con comandos como sendln, waitfor y pause. Incluye un editor básico con syntax highlighting dentro de la app.
```

### Para transferencia de archivos:
```
Añade soporte de transferencia de archivos XMODEM y ZMODEM al módulo file-transfer. Incluye una barra de progreso visual en la UI.
```

### Para mejorar la terminal:
```
Mejora el emulador de terminal con soporte completo de ANSI escape codes, colores 256, scroll buffer de 10000 líneas, y selección de texto con copy/paste.
```

### Para logging:
```
Implementa el sistema de logging con opciones de texto plano y HTML con colores. Incluye timestamps configurables y rotación automática de logs.
```

### Para pulir la UI:
```
Añade tema oscuro/claro, configuración de fuentes, atajos de teclado, e internacionalización español/inglés a la interfaz.
```

---

## Notas de Uso

- **Pega el prompt principal** cuando inicies Claude Code en la raíz de tu proyecto en IntelliJ IDEA
- **Usa los prompts de seguimiento** uno por uno conforme vayas avanzando
- Claude Code creará los archivos y tú podrás revisar cada cambio en el visor de diferencias de IntelliJ IDEA antes de aceptarlo
- Si algo no te gusta, dile a Claude qué cambiar antes de pasar al siguiente módulo
