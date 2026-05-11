package com.opentermx.common.ai

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Excepción pública de los providers. Cualquier fallo en `sendPrompt` o `discoverModels`
 * se traduce a esta clase para que la capa UI/macros pueda inspeccionar [error] sin
 * depender de implementaciones concretas (OkHttp, Jackson, etc.).
 */
class LlmException(
    val error: LlmError,
    val httpCode: Int = 0,
    val body: String = "",
) : RuntimeException(buildMessage(error, httpCode))

private fun buildMessage(error: LlmError, httpCode: Int): String =
    if (httpCode > 0) "${error.code} (HTTP $httpCode): ${error.message}"
    else "${error.code}: ${error.message}"

/**
 * Traduce excepciones de red y códigos HTTP a [LlmError] localizables
 * según la tabla de la spec v4 (línea 346, “Test connection”).
 */
object LlmErrorMapper {

    fun fromHttp(code: Int): LlmError = when (code) {
        401 -> LlmError.Unauthorized
        402 -> LlmError.PaymentRequired
        403 -> LlmError.Forbidden
        404 -> LlmError.ModelNotFound
        429 -> LlmError.RateLimited
        in 500..599 -> LlmError.ServerError(code)
        else -> LlmError.Other("HTTP $code")
    }

    fun fromException(t: Throwable): LlmError = when {
        t is LlmException -> t.error
        t is SocketTimeoutException -> LlmError.Timeout
        t is SSLException -> LlmError.SslError
        t is ConnectException -> LlmError.ConnectionRefused
        t is UnknownHostException -> LlmError.DnsFailed
        t is IOException -> LlmError.Other(t.message ?: "I/O error")
        else -> LlmError.Other(t.message ?: t::class.java.simpleName)
    }
}
