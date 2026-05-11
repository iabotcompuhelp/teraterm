package com.opentermx.common.ai

data class ChatMessage(
    val role: Role,
    val content: String,
) {
    enum class Role { SYSTEM, USER, ASSISTANT }
}

data class LlmRequest(
    val model: String,
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    val maxTokens: Int = 2048,
    val timeoutSeconds: Int = 60,
)

data class LlmResponse(
    val text: String,
    val model: String,
    val latencyMillis: Long,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

/**
 * Resultado de [LLMProvider.testConnection]. Si [error] es null, la conexión fue exitosa.
 */
data class ConnectionResult(
    val success: Boolean,
    val provider: ProviderKind,
    val model: String,
    val latencyMillis: Long = 0,
    val error: LlmError? = null,
)

/**
 * Errores categorizados — mapeo directo a la tabla de la spec v4 (línea 346).
 * El módulo `app` traduce el [code] a una clave i18n (`error.ai.*`).
 */
sealed class LlmError(val code: String, val message: String) {
    object Unauthorized : LlmError("401", "API Key inválida o expirada")
    object Forbidden : LlmError("403", "Acceso denegado — permisos insuficientes")
    object RateLimited : LlmError("429", "Límite de peticiones excedido")
    object PaymentRequired : LlmError("402", "Sin créditos o plan de pago")
    object ModelNotFound : LlmError("404", "Modelo no encontrado")
    class ServerError(val httpCode: Int) : LlmError("5xx", "Error del servidor del provider ($httpCode)")
    object Timeout : LlmError("timeout", "Timeout — sin respuesta del servidor")
    object SslError : LlmError("ssl", "Error de certificado SSL")
    object ConnectionRefused : LlmError("connRefused", "Conexión rechazada")
    object DnsFailed : LlmError("dns", "No se pudo resolver el dominio")
    object InvalidKeyFormat : LlmError("keyFormat", "Formato de API Key incorrecto")
    object NoModels : LlmError("noModels", "No se encontraron modelos instalados")
    object OutOfMemory : LlmError("oom", "Memoria insuficiente para cargar el modelo")
    class Other(val detail: String) : LlmError("other", detail)
}
