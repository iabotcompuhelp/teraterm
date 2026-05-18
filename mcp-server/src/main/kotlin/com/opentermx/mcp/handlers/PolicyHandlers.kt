package com.opentermx.mcp.handlers

import com.opentermx.mcp.handlers.McpToolException.ErrorCode.INVALID_ARGUMENT
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.inventory.InventoryDevice
import com.opentermx.mcp.inventory.InventoryProvider
import com.opentermx.mcp.snapshots.Snapshot
import com.opentermx.mcp.snapshots.SnapshotStore
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import com.opentermx.policy.DeviceConfigParsers
import com.opentermx.policy.PolicyException
import com.opentermx.policy.PolicyLoader
import com.opentermx.policy.PolicyRegistry
import com.opentermx.policy.PolicyReportRenderer
import com.opentermx.policy.RuleEvaluator
import java.nio.file.Path

/**
 * Phase 3 Fase 5 — handlers de policy. Todos read-only y determinísticos: misma
 * config + misma policy → mismo output. Pueden invocarse desde el rol COMPLIANCE
 * (que las consume como insumo de su decisión) o VALIDATOR.
 */
class PolicyLoadHandler(
    private val registry: PolicyRegistry,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.POLICY_LOAD

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val path = Args.optionalString(args, "path")
        val yaml = Args.optionalString(args, "yaml")
        val provided = listOfNotNull(path?.takeIf { it.isNotBlank() }, yaml?.takeIf { it.isNotBlank() })
        if (provided.size != 1) {
            throw McpToolException(INVALID_ARGUMENT,
                "Debe pasarse exactamente uno de: `path` o `yaml`")
        }
        val policy = try {
            if (path != null && path.isNotBlank()) PolicyLoader.fromPath(Path.of(path))
            else PolicyLoader.fromYamlString(yaml!!)
        } catch (e: PolicyException) {
            throw McpToolException(INVALID_ARGUMENT, e.message ?: "policy inválida")
        }
        registry.register(policy)
        return linkedMapOf(
            "name" to policy.policy.name,
            "version" to policy.policy.version,
            "ruleCount" to policy.rules.size,
        )
    }
}

class PolicyListHandler(
    private val registry: PolicyRegistry,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.POLICY_LIST

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val items = registry.list().map { p ->
            linkedMapOf<String, Any?>(
                "name" to p.policy.name,
                "version" to p.policy.version,
                "ruleCount" to p.rules.size,
                "deviceTypes" to (p.policy.appliesTo?.deviceTypes ?: emptyList<String>()),
            )
        }
        return linkedMapOf("policies" to items)
    }
}

class PolicyEvaluateHandler(
    private val registry: PolicyRegistry,
    private val snapshotStore: SnapshotStore,
    private val inventory: InventoryProvider,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.POLICY_EVALUATE

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val policyName = Args.requireString(args, "policyName")
        val deviceAlias = Args.optionalString(args, "deviceAlias")
        val snapshotIdOverride = Args.optionalString(args, "snapshotId")
        val withMarkdown = (args["markdown"] as? Boolean) ?: false

        val policy = registry.get(policyName)
            ?: throw McpToolException(NOT_FOUND, "Policy `$policyName` no registrada — usá policy_load")

        val snapshot = resolveSnapshot(deviceAlias, snapshotIdOverride)
        val device = deviceAlias?.let { inventory.byAlias(it) }
        val parser = DeviceConfigParsers.forDeviceType(device?.deviceType ?: snapshot.deviceAlias?.let {
            inventory.byAlias(it)?.deviceType
        })
        val eval = RuleEvaluator.evaluate(policy, snapshot.content, deviceAlias ?: snapshot.deviceAlias, parser)
        val out = LinkedHashMap(PolicyReportRenderer.toJson(eval))
        if (withMarkdown) out["markdown"] = PolicyReportRenderer.toMarkdown(eval)
        return out
    }

    private fun resolveSnapshot(deviceAlias: String?, snapshotIdOverride: String?): Snapshot {
        if (!snapshotIdOverride.isNullOrBlank()) {
            return snapshotStore.load(snapshotIdOverride)
                ?: throw McpToolException(NOT_FOUND, "Snapshot `$snapshotIdOverride` no existe")
        }
        if (deviceAlias.isNullOrBlank()) {
            throw McpToolException(INVALID_ARGUMENT,
                "Especificá `deviceAlias` o `snapshotId` para resolver el snapshot a evaluar")
        }
        val latest = snapshotStore.listForDevice(null, deviceAlias, null)
            .maxByOrNull { it.timestampMillis }
            ?: throw McpToolException(NOT_FOUND,
                "No hay snapshots para deviceAlias=`$deviceAlias`. Capturalo con `snapshot_create`.")
        return latest
    }
}

class PolicyAuditHandler(
    private val registry: PolicyRegistry,
    private val snapshotStore: SnapshotStore,
    private val inventory: InventoryProvider,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.POLICY_AUDIT

    @Suppress("UNCHECKED_CAST")
    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val policyName = Args.requireString(args, "policyName")
        val tagsAny = (args["tagsAny"] as? List<*>)?.mapNotNull { it as? String }
        val withMarkdown = (args["markdown"] as? Boolean) ?: false

        val policy = registry.get(policyName)
            ?: throw McpToolException(NOT_FOUND, "Policy `$policyName` no registrada — usá policy_load")

        val candidates = collectCandidates(policy, tagsAny)
        val evaluations = candidates.map { device ->
            val latest = snapshotStore.listForDevice(null, device.alias, null)
                .maxByOrNull { it.timestampMillis }
            if (latest == null) {
                com.opentermx.policy.PolicyEvaluation(
                    policyName = policy.policy.name,
                    policyVersion = policy.policy.version,
                    deviceAlias = device.alias,
                    target = policy.rules.firstOrNull()?.target ?: "running_config",
                    results = policy.rules.map { rule ->
                        com.opentermx.policy.RuleResult(
                            ruleId = rule.id, severity = rule.severity,
                            status = com.opentermx.policy.RuleStatus.WARN,
                            message = "Sin snapshot para evaluar — capturar con snapshot_create",
                        )
                    },
                )
            } else {
                val parser = DeviceConfigParsers.forDeviceType(device.deviceType)
                RuleEvaluator.evaluate(policy, latest.content, device.alias, parser)
            }
        }
        val out = LinkedHashMap(PolicyReportRenderer.toJsonAudit(evaluations))
        if (withMarkdown) out["markdown"] = PolicyReportRenderer.toMarkdownAudit(evaluations)
        return out
    }

    /**
     * Aplica filtros AND:
     *  - `applies_to.device_types`: matcheo exacto (case-insensitive).
     *  - `applies_to.tags_any` + `tagsAny` del input: AND. ANY-of dentro de cada lista.
     */
    private fun collectCandidates(policy: com.opentermx.policy.Policy, tagsAny: List<String>?): List<InventoryDevice> {
        val deviceTypes = policy.policy.appliesTo?.deviceTypes ?: emptyList()
        val policyTags = policy.policy.appliesTo?.tagsAny ?: emptyList()
        var devices = inventory.list()
        if (deviceTypes.isNotEmpty()) {
            devices = devices.filter { d ->
                d.deviceType != null && deviceTypes.any { it.equals(d.deviceType, ignoreCase = true) }
            }
        }
        if (policyTags.isNotEmpty()) {
            devices = devices.filter { d -> d.tags.any { it in policyTags } }
        }
        if (!tagsAny.isNullOrEmpty()) {
            devices = devices.filter { d -> d.tags.any { it in tagsAny } }
        }
        return devices
    }
}
