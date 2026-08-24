package com.opentermx.server

import java.util.concurrent.CountDownLatch
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("OpenTermXServer")
    val config = try {
        ServerConfig.fromEnvironment()
    } catch (e: IllegalArgumentException) {
        log.error("Configuración inválida: {}", e.message)
        return
    }

    val stopped = CountDownLatch(1)
    val runtime = HeadlessServerRuntime(config)
    Runtime.getRuntime().addShutdownHook(Thread({
        runtime.close()
        stopped.countDown()
    }, "opentermx-server-shutdown"))

    runtime.start()
    val binding = checkNotNull(runtime.binding())
    log.info(
        "OpenTermX control plane listo en {}:{} (auth={}, readOnly={}, dataDir={})",
        binding.host,
        binding.port,
        binding.hasAuth,
        config.readOnly,
        config.dataDir,
    )
    log.info("Persistencia PostgreSQL: {}", if (config.database == null) "deshabilitada" else "habilitada")
    if (config.agentToken != null || config.agentCredentialsFile != null) {
        log.info("Gateway de agentes listo en {}:{}", config.bindAddress, config.agentPort)
    } else {
        log.info("Gateway de agentes deshabilitado (no hay credenciales de agentes)")
    }
    stopped.await()
}
