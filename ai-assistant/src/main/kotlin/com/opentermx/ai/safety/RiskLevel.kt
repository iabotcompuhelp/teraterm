package com.opentermx.ai.safety

/**
 * Semáforo de riesgo del panel de revisión de comandos generados por IA.
 * Definido en la spec v4 § Panel de Revisión de Comandos.
 */
enum class RiskLevel(val colorHex: String) {
    SAFE("#2e7d32"),        // verde — lectura (show, display, ping, traceroute)
    CONFIG("#f9a825"),      // amarillo — configuración (configure, interface, vlan, ip, set, …)
    DANGEROUS("#c62828");   // rojo — destructivo (erase, delete, reload, format, …)
}

/**
 * Una línea de comando clasificada con su nivel de riesgo y explicación opcional.
 */
data class ClassifiedCommand(
    val raw: String,
    val risk: RiskLevel,
    val explanation: String? = null,
)
