package com.opentermx.mcp.adapters

import com.fasterxml.jackson.databind.ObjectMapper
import com.opentermx.mgmt.AdapterAvailability
import com.opentermx.mgmt.AdapterDescriptor
import com.opentermx.mgmt.AdapterResult
import com.opentermx.mgmt.DeviceRef
import com.opentermx.mgmt.ManagementAdapter
import com.opentermx.mgmt.MgmtMethod
import com.opentermx.mgmt.OperationDescriptor
import com.opentermx.mgmt.OperationKind
import com.opentermx.mgmt.ProposalTicket
import com.opentermx.mgmt.ReadOperation
import com.opentermx.mgmt.WriteOperation
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import org.slf4j.LoggerFactory

/**
 * Adaptador REST de LECTURA (Fase 6C.2). HTTP puro desde la JVM (sin el bridge Python):
 * las operaciones de lectura las declara el catálogo del modelo (metadata.restApi) y la
 * configuración por dispositivo (baseUrl/verifyTls) vive en `device_management_settings`.
 *
 * Para 6C.2 solo se implementa el estilo de auth `session-cookie` (Aruba 2930F / AOS-S):
 * POST al loginPath con {userName,password} → cookie de sesión → GET del recurso → DELETE
 * para cerrar la sesión (el equipo limita las sesiones REST concurrentes). Las escrituras
 * (POST/PUT/PATCH/DELETE de gestión) NO se ejecutan acá: `proposeWrite` produce un ticket
 * para el ApprovalGate (cableado en 6C.3).
 *
 * El cuerpo de la respuesta es contenido de una PLATAFORMA EXTERNA → dato no confiable;
 * el handler lo marca con `contentOrigin`. El secreto jamás viaja en un mensaje de error.
 */
class RestAdapter(
    private val catalogMetaOf: (deviceId: Long) -> RestCatalogMeta?,
    private val deviceConfigOf: (deviceId: Long) -> RestDeviceConfig?,
    private val credentialsOf: (deviceId: Long) -> RestCredentials?,
    /** Inyectable para tests: cliente HTTP según verifyTls. */
    private val clientFactory: (verifyTls: Boolean) -> HttpClient = ::defaultClient,
) : ManagementAdapter {

    data class RestOp(val id: String, val httpMethod: String, val path: String)
    data class RestCatalogMeta(val authStyle: String, val loginPath: String?, val readOps: List<RestOp>)
    data class RestDeviceConfig(val baseUrl: String, val verifyTls: Boolean = true)
    data class RestCredentials(val username: String, val password: String)

    override val method = MgmtMethod.REST_API

    /** El runtime (cliente HTTP de la JVM) siempre existe. La config por device se valida al usar. */
    override fun isAvailable(): AdapterAvailability = AdapterAvailability.Available

    override fun describe(device: DeviceRef): AdapterDescriptor {
        val ops = catalogMetaOf(device.deviceId)?.readOps?.map {
            OperationDescriptor(it.id, "GET ${it.path}", OperationKind.READ, timeoutSeconds = 15)
        } ?: emptyList()
        return AdapterDescriptor(MgmtMethod.REST_API, ops)
    }

    override suspend fun executeRead(device: DeviceRef, op: ReadOperation): AdapterResult {
        val meta = catalogMetaOf(device.deviceId)
            ?: return AdapterResult.Failure("el modelo del catálogo no declara una REST API")
        val config = deviceConfigOf(device.deviceId)
            ?: return AdapterResult.Failure("REST no configurado en el dispositivo (falta baseUrl)")
        val creds = credentialsOf(device.deviceId)
            ?: return AdapterResult.Failure("sin credenciales REST para el dispositivo")
        val restOp = meta.readOps.firstOrNull { it.id == op.id }
            ?: return AdapterResult.Failure("operación REST de lectura desconocida: `${op.id}`")
        // Defensa #56 a nivel HTTP: una operación de lectura DEBE ser GET/HEAD.
        if (restOp.httpMethod.uppercase() !in READ_HTTP_METHODS) {
            return AdapterResult.Failure(
                "la operación `${op.id}` no es de lectura (HTTP ${restOp.httpMethod}); usá propose_adapter_write",
            )
        }
        if (!meta.authStyle.equals("session-cookie", ignoreCase = true)) {
            return AdapterResult.Failure("authStyle `${meta.authStyle}` no soportado todavía (6C.2: solo session-cookie)")
        }
        return runCatching {
            executeSessionCookie(config, creds, meta, restOp)
        }.getOrElse { AdapterResult.Failure(scrub(it.message ?: it.javaClass.simpleName, creds.password)) }
    }

    override suspend fun proposeWrite(device: DeviceRef, op: WriteOperation): ProposalTicket {
        // 6C.3 cablea el ticket REST de escritura al ApprovalGate. Acá: el payload literal.
        return ProposalTicket(
            method = MgmtMethod.REST_API,
            operationId = op.id,
            deviceHostname = device.hostname,
            literalPayload = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(op.payload),
            rationale = op.rationale,
        )
    }

    // ------------------------------------------------------------------ internals

    private fun executeSessionCookie(
        config: RestDeviceConfig,
        creds: RestCredentials,
        meta: RestCatalogMeta,
        op: RestOp,
    ): AdapterResult {
        val client = clientFactory(config.verifyTls)
        val base = config.baseUrl.trimEnd('/')
        val loginPath = meta.loginPath ?: return AdapterResult.Failure("el catálogo no declara loginPath para REST")

        // 1) login → cookie de sesión.
        val loginBody = mapper.writeValueAsString(mapOf("userName" to creds.username, "password" to creds.password))
        val loginResp = client.send(
            HttpRequest.newBuilder(URI.create(base + loginPath))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (loginResp.statusCode() !in 200..299) {
            return AdapterResult.Failure("login REST falló: HTTP ${loginResp.statusCode()}")
        }
        val cookie = sessionCookie(loginResp)
            ?: return AdapterResult.Failure("login REST no devolvió cookie de sesión")

        try {
            // 2) GET del recurso con la cookie.
            val getResp = client.send(
                HttpRequest.newBuilder(URI.create(base + op.path))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("Cookie", cookie)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (getResp.statusCode() !in 200..299) {
                return AdapterResult.Failure("GET `${op.path}` falló: HTTP ${getResp.statusCode()}")
            }
            return AdapterResult.Success(
                linkedMapOf(
                    "operation" to op.id,
                    "httpStatus" to getResp.statusCode(),
                    "path" to op.path,
                    "body" to getResp.body().take(MAX_BODY_CHARS),
                )
            )
        } finally {
            // 3) logout best-effort (el equipo limita sesiones REST concurrentes).
            runCatching {
                client.send(
                    HttpRequest.newBuilder(URI.create(base + loginPath))
                        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                        .header("Cookie", cookie)
                        .DELETE()
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
            }.onFailure { log.debug("logout REST best-effort falló: {}", it.message) }
        }
    }

    /** Cookie de sesión del header Set-Cookie (toma el primer par nombre=valor). */
    private fun sessionCookie(resp: HttpResponse<*>): String? =
        resp.headers().allValues("set-cookie").firstOrNull()?.substringBefore(';')?.takeIf { it.contains('=') }

    private fun scrub(text: String, secret: String): String =
        if (secret.isBlank()) text else text.replace(secret, "***")

    companion object {
        private val log = LoggerFactory.getLogger(RestAdapter::class.java)
        private val mapper = ObjectMapper()
        private const val TIMEOUT_SECONDS = 15L
        private const val MAX_BODY_CHARS = 64 * 1024
        private val READ_HTTP_METHODS = setOf("GET", "HEAD")

        /** Cliente por default; con `verifyTls=false` instala trust-all CON warning (error #25). */
        fun defaultClient(verifyTls: Boolean): HttpClient {
            val b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            if (!verifyTls) {
                log.warn("RestAdapter con verifyTls=false: certificados NO validados (aceptable solo en labs).")
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
                    override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                b.sslContext(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) })
            }
            return b.build()
        }

        /** Parsea el bloque `restApi` de la metadata del catálogo a [RestCatalogMeta], o null. */
        fun metaFromCatalog(metadataJson: String): RestCatalogMeta? = runCatching {
            @Suppress("UNCHECKED_CAST")
            val root = mapper.readValue(metadataJson, Map::class.java) as Map<String, Any?>
            val rest = root["restApi"] as? Map<*, *> ?: return null
            val authStyle = rest["authStyle"] as? String ?: return null
            val loginPath = rest["loginPath"] as? String
            val examples = (rest["readExamples"] as? List<*>).orEmpty().mapNotNull { e ->
                val m = e as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? String ?: return@mapNotNull null
                RestOp(id, (m["method"] as? String) ?: "GET", (m["path"] as? String) ?: return@mapNotNull null)
            }
            RestCatalogMeta(authStyle, loginPath, examples)
        }.getOrNull()
    }
}
