package com.opentermx.mcp.snapshots

/**
 * Phase 3 Fase 4 — propone los comandos que llevarían un device al estado que tenía en
 * `snapshotBefore`, a partir del diff entre `snapshotBefore` y `snapshotAfter`.
 *
 * IMPORTANTE: la lista propuesta NO se ejecuta automáticamente. La devolvemos al
 * operator/compliance loop para que decida. Los rollbacks de network devices son
 * device-dependent y, en algunos casos, requieren conocimiento de contexto que el motor
 * no tiene.
 *
 * Cobertura inicial:
 *  - **cisco_ios / hpe_comware / huawei_vrp**: cada línea agregada en `after` se revierte
 *    con `no <línea>`; cada línea removida en `after` se reaplica tal cual.
 *  - **linux_shell** y otros: devolvemos un placeholder explícito + el diff bruto, sin
 *    inventar comandos.
 *
 * Limitaciones documentadas en `docs/snapshots.md`.
 */
object RollbackProposer {

    data class RollbackPlan(
        val deviceType: String?,
        val supported: Boolean,
        val commands: List<String>,
        val notes: List<String>,
    )

    private val NETWORK_TYPES = setOf("cisco_ios", "hpe_comware", "huawei_vrp")

    fun propose(before: Snapshot, after: Snapshot, deviceType: String?): RollbackPlan {
        if (before.contentHash == after.contentHash) {
            return RollbackPlan(deviceType, supported = true,
                commands = emptyList(),
                notes = listOf("Snapshots idénticos — no hay nada que revertir."))
        }
        val diff = SnapshotDiffer.diff(before, after, deviceType)
        if (deviceType in NETWORK_TYPES) {
            return proposeNetwork(diff, deviceType)
        }
        return RollbackPlan(
            deviceType = deviceType,
            supported = false,
            commands = emptyList(),
            notes = listOf(
                "Generación automática de rollback no soportada para deviceType=`$deviceType`.",
                "Aplicar manualmente revisando el diff (+${diff.addedLines.size} / -${diff.removedLines.size} líneas).",
            ),
        )
    }

    private fun proposeNetwork(diff: SnapshotDiffer.DiffResult, deviceType: String?): RollbackPlan {
        val cmds = mutableListOf<String>()
        val notes = mutableListOf<String>()

        // Reglas:
        //  - Línea agregada y NO es header (indentada o no) → "no <línea trim>".
        //  - Línea removida → reaplicarla tal cual (mantenemos indentación original).
        // El LLM/operator debe envolver con `configure terminal` / `exit` según convenga;
        // el motor no impone modo enable/config porque eso depende del device state.
        for (line in diff.addedLines) {
            val effective = line.trim()
            if (effective.isBlank()) continue
            if (effective.startsWith("no ", ignoreCase = true)) {
                // Ya es una negación — revertir es quitar la negación.
                cmds += effective.removePrefix("no ").removePrefix("No ").trim()
            } else {
                cmds += "no $effective"
            }
        }
        for (line in diff.removedLines) {
            val effective = line.trim()
            if (effective.isNotBlank()) cmds += effective
        }

        if (cmds.isEmpty()) {
            notes += "Diff sin cambios procesables — verifica los snapshots."
        } else {
            notes += "Generado por heurística: cada línea agregada se invierte con `no <línea>`, las removidas se reaplican."
            notes += "Es posible que necesites envolver con `configure terminal` / `end` según el modo actual del device."
        }

        return RollbackPlan(deviceType, supported = true, commands = cmds, notes = notes)
    }
}
