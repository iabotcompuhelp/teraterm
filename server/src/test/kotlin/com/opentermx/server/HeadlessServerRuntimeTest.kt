package com.opentermx.server

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HeadlessServerRuntimeTest {
    @Test
    fun `headless catalog exposes only control plane tools`() {
        val config = ServerConfig("127.0.0.1", 8765, Files.createTempDirectory("opentermx-server-test"), null)
        val names = HeadlessServerRuntime(config).use { runtime ->
            runtime.handlers.map { it.definition.name }
        }

        assertEquals(
            setOf(
                "list_sessions",
                "inspect_session",
                "start_operation",
                "current_operation",
                "resume_operation",
                "export_operation_handoff",
                "end_operation",
            ),
            names.toSet(),
        )
    }
}
