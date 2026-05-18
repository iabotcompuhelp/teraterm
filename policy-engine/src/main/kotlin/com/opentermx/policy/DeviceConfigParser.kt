package com.opentermx.policy

/**
 * Phase 3 Fase 5 — hook para que un device_type pueda transformar el contenido de un
 * snapshot antes de que `RuleEvaluator` corra los patterns. El default es identidad —
 * devolvemos el texto bruto y dejamos que los patterns operen line-by-line.
 *
 * Implementaciones futuras (NO en esta fase): un parser de Cisco IOS que devuelva las
 * líneas normalizadas (sin comentarios, sin separadores `!`, con indentación consistente)
 * o que materialice la config como árbol de secciones. Por ahora la API queda definida
 * para que un PR posterior agregue parsers sin tocar [RuleEvaluator].
 */
interface DeviceConfigParser {
    /** Devuelve las líneas a evaluar. La impl default solo splitea por `\n`. */
    fun lines(rawContent: String): List<String>

    object Default : DeviceConfigParser {
        override fun lines(rawContent: String): List<String> = rawContent.lines()
    }
}

/**
 * Registry estático de parsers por device_type. Vacío por default; un módulo que agregue
 * parsers específicos llama `register(deviceType, parser)` al cargar.
 */
object DeviceConfigParsers {

    private val byType = mutableMapOf<String, DeviceConfigParser>()

    @Synchronized
    fun register(deviceType: String, parser: DeviceConfigParser) {
        byType[deviceType.lowercase()] = parser
    }

    @Synchronized
    fun forDeviceType(deviceType: String?): DeviceConfigParser =
        deviceType?.lowercase()?.let { byType[it] } ?: DeviceConfigParser.Default
}
