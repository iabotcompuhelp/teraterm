package com.opentermx.policy

import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Cache in-memory de policies cargadas. El handler de `policy_load` la rellena; los
 * handlers de `policy_evaluate` / `policy_audit` la consultan.
 *
 * No persistimos a disco — el operador re-loadea al arrancar el server si quiere
 * persistencia. Es trivial agregar storage si llegamos a necesitarlo.
 */
class PolicyRegistry {

    private val log = LoggerFactory.getLogger(javaClass)
    private val byName = ConcurrentHashMap<String, Policy>()

    fun register(policy: Policy): Policy {
        byName[policy.policy.name] = policy
        log.info("Policy `{}` v{} registrada con {} regla(s)",
            policy.policy.name, policy.policy.version, policy.rules.size)
        return policy
    }

    fun get(name: String): Policy? = byName[name]

    fun list(): List<Policy> = byName.values.toList()

    fun unregister(name: String): Policy? = byName.remove(name)
}
