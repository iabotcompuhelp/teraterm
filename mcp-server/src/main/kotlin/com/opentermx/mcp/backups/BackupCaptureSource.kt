package com.opentermx.mcp.backups

import com.opentermx.ai.context.Vendor

data class CapturedConfiguration(
    val vendor: Vendor,
    val content: String,
    val source: String,
)

/** Límite de captura: la implementación debe consultar el equipo, nunca el buffer visual. */
fun interface BackupCaptureSource {
    suspend fun capture(deviceAlias: String?, sessionId: String?): CapturedConfiguration
}
