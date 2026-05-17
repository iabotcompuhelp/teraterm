package com.opentermx.app.ui.rdp

import org.slf4j.LoggerFactory

/**
 * Lanza sesiones RDP usando el cliente nativo de Microsoft (`mstsc.exe`) y guarda credenciales
 * en el Windows Credential Manager via `cmdkey`. Resultado UX:
 *  1. El operador hace doble-click en la entrada guardada.
 *  2. `cmdkey /generic:TERMSRV/<host> /user:<user> /pass:<password>` registra la cred.
 *  3. `mstsc.exe /v:<host>:<port>` levanta la ventana RDP nativa, que **lee la cred**
 *     automáticamente y conecta sin pedir nada.
 *
 * Limitaciones:
 *  - Solo Windows. [isSupported] devuelve `false` en Mac/Linux y los handlers no se invocan.
 *  - La cred queda persistida en el Credential Manager del usuario del SO. Se limpia con
 *    [deleteCredential] (ej. al borrar la entrada del panel "Conexiones guardadas").
 *  - El password aparece brevemente en el command line de `cmdkey` (visible en Task Manager).
 *    Inevitable sin reescribir esto con JNA llamando `CredWriteW` directamente.
 */
object RdpLauncher {

    private val log = LoggerFactory.getLogger(javaClass)

    val isSupported: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    /**
     * Lanza una sesión RDP. Si [password] no está vacía, guarda primero la cred en Credential
     * Manager — mstsc la encuentra y conecta sin prompt. Si está vacía, mstsc abrirá su propio
     * prompt al conectar.
     *
     * @return `true` si el proceso `mstsc.exe` se lanzó (no confirma que la conexión
     *         haya tenido éxito — eso lo gestiona el cliente RDP).
     */
    fun launch(host: String, port: Int, username: String, password: String): Boolean {
        if (!isSupported) {
            log.warn("RDP no soportado en este OS: {}", System.getProperty("os.name"))
            return false
        }
        if (host.isBlank()) return false

        if (username.isNotBlank() && password.isNotEmpty()) {
            storeCredential(host, username, password)
        }
        return runMstsc(host, port)
    }

    /**
     * Borra la entrada `TERMSRV/<host>` del Credential Manager. Llamarse al eliminar la
     * entrada del panel "Conexiones guardadas" para simetría — destildar/eliminar = on/off
     * tanto en OpenTermX como en el SO.
     */
    fun deleteCredential(host: String) {
        if (!isSupported || host.isBlank()) return
        runCmdkey(arrayOf("/delete:TERMSRV/$host"), reason = "delete cred")
    }

    private fun storeCredential(host: String, user: String, password: String) {
        // Las creds RDP en Windows se buscan bajo el target name "TERMSRV/<host>". mstsc
        // no usa el puerto en el lookup, así que guardar por hostname/IP alcanza para
        // cualquier port. Si user pasa "DOMAIN\\user" se respeta el escapeo.
        runCmdkey(
            arrayOf("/generic:TERMSRV/$host", "/user:$user", "/pass:$password"),
            reason = "store cred",
        )
    }

    private fun runCmdkey(args: Array<String>, reason: String): Boolean {
        return try {
            val cmd = mutableListOf<String>("cmdkey").apply { addAll(args) }
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val proc = pb.start()
            val finished = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                log.warn("cmdkey ({}) timeout", reason)
                return false
            }
            if (proc.exitValue() != 0) {
                log.warn("cmdkey ({}) exit={}", reason, proc.exitValue())
                return false
            }
            true
        } catch (t: Throwable) {
            log.warn("cmdkey ({}) falló: {}", reason, t.message)
            false
        }
    }

    private fun runMstsc(host: String, port: Int): Boolean {
        return try {
            val endpoint = if (port == DEFAULT_RDP_PORT) host else "$host:$port"
            // /v:<endpoint> = host destino. No usamos /f (full-screen) ni /w/h por default
            // para no imponer geometría; mstsc abre con su última config del usuario.
            ProcessBuilder("mstsc.exe", "/v:$endpoint").start()
            true
        } catch (t: Throwable) {
            log.error("mstsc.exe falló para {}:{} -> {}", host, port, t.message)
            false
        }
    }

    const val DEFAULT_RDP_PORT = 3389
}
