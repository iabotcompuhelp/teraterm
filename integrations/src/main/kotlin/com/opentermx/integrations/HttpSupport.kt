package com.opentermx.integrations

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
 * Soporte HTTP común de los conectores (Fase 4):
 *  - timeout de 10 s por request;
 *  - 2 reintentos con backoff SOLO para 5xx y errores de transporte — un 4xx es un
 *    problema del request, reintentar no lo arregla (regla del spec);
 *  - `verifyTls=false` (explícito en la config, jamás default) instala un trust-all
 *    CON warning en el log — error #25 del catálogo;
 *  - [scrub] garantiza que el secreto no viaje en mensajes de error.
 */
internal object HttpSupport {

    private val log = LoggerFactory.getLogger(javaClass)

    const val TIMEOUT_SECONDS = 10L
    const val MAX_RETRIES = 2
    private val BACKOFF_MILLIS = listOf(300L, 900L)

    fun newClient(verifyTls: Boolean, integrationName: String): HttpClient {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        if (!verifyTls) {
            log.warn(
                "Integración `{}` con verify_tls=false: certificados NO validados. " +
                    "Aceptable solo en labs — configuralo en true en cuanto puedas.",
                integrationName,
            )
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
            builder.sslContext(ssl)
        }
        return builder.build()
    }

    /**
     * POST/GET con reintentos. Devuelve el body como String si el status es 2xx.
     * Lanza [IntegrationException] con mensaje depurado en cualquier otro caso.
     */
    fun execute(
        client: HttpClient,
        request: HttpRequest,
        secret: String,
        what: String,
    ): String {
        var lastError: String? = null
        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) Thread.sleep(BACKOFF_MILLIS[attempt - 1])
            try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                val code = response.statusCode()
                when {
                    code in 200..299 -> return response.body()
                    code in 500..599 -> {
                        lastError = "HTTP $code del servidor"
                        continue // 5xx: reintentar con backoff
                    }
                    else -> throw IntegrationException(
                        "$what falló: HTTP $code — ${scrub(response.body().take(300), secret)}"
                    )
                }
            } catch (e: IntegrationException) {
                throw e
            } catch (e: Exception) {
                // Transporte (timeout, conexión): reintentable.
                lastError = scrub(e.message ?: e.javaClass.simpleName, secret)
            }
        }
        throw IntegrationException("$what falló tras ${MAX_RETRIES + 1} intentos: $lastError")
    }

    fun request(uri: String, timeoutSeconds: Long = TIMEOUT_SECONDS): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(timeoutSeconds))

    /** El secreto JAMÁS sale en un mensaje: toda cadena que lo contenga lo enmascara. */
    fun scrub(text: String, secret: String): String =
        if (secret.isBlank()) text else text.replace(secret, "***")
}
