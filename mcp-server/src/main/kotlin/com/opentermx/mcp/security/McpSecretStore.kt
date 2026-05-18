package com.opentermx.mcp.security

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import org.slf4j.LoggerFactory

/**
 * Phase 3 Fase 3 — gestión del secreto HMAC usado para firmar [ApprovalToken].
 *
 * Persistencia: archivo de 32 bytes random en `~/.opentermx/mcp-secret.key`. Auto-generado
 * en el primer arranque del servidor. Permisos `0600` en filesystems POSIX (Linux, macOS);
 * en Windows el atributo no aplica y se omite (el archivo queda con los permisos default
 * del directorio del usuario — aceptable en máquinas de operación individual).
 *
 * Rotación: si alguna vez se necesita, basta con borrar el archivo y reiniciar el server.
 * Eso invalida TODOS los tokens previamente emitidos — la spec MCP no expone refresh.
 */
class McpSecretStore(
    private val path: Path = defaultPath(),
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    /** Lee el secreto del disco. Si no existe, lo genera. Thread-safe vía sincronización. */
    @Synchronized
    fun loadOrCreate(): ByteArray {
        if (Files.isRegularFile(path)) {
            val bytes = Files.readAllBytes(path)
            if (bytes.size >= 32) {
                return bytes
            }
            log.warn("Secret en $path tiene tamaño insuficiente (${bytes.size}); regenerando")
        }
        Files.createDirectories(path.parent)
        val fresh = ByteArray(32).also { random.nextBytes(it) }
        Files.write(path, fresh)
        applyPosixIfPossible()
        log.info("MCP secret HMAC regenerado en {}", path)
        return fresh
    }

    /**
     * Aplica `chmod 600` cuando el filesystem lo soporta. En Windows la JVM puede tirar
     * UnsupportedOperationException o IOException — capturamos y seguimos (la ACL del
     * directorio %USERPROFILE% ya restringe acceso).
     */
    private fun applyPosixIfPossible() {
        runCatching {
            val perms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            Files.setPosixFilePermissions(path, perms)
        }.onFailure { e ->
            log.debug("No se pudieron aplicar permisos POSIX 0600 a {}: {}", path, e.message)
        }
    }

    companion object {
        fun defaultPath(): Path =
            Path.of(System.getProperty("user.home"), ".opentermx", "mcp-secret.key")
    }
}
