package com.opentermx.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ServerConfigTest {
    @Test
    fun `defaults are loopback and unauthenticated`() {
        val config = ServerConfig.fromEnvironment(emptyMap())

        assertEquals("127.0.0.1", config.bindAddress)
        assertEquals(8765, config.port)
        assertNull(config.token)
    }

    @Test
    fun `public bind requires a token`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromEnvironment(mapOf("OPENTERMX_BIND" to "0.0.0.0"))
        }
    }

    @Test
    fun `environment overrides network and storage settings`() {
        val config = ServerConfig.fromEnvironment(
            mapOf(
                "OPENTERMX_BIND" to "0.0.0.0",
                "OPENTERMX_PORT" to "9876",
                "OPENTERMX_DATA_DIR" to "build/server-data",
                "OPENTERMX_MCP_TOKEN" to "test-token",
            ),
        )

        assertEquals("0.0.0.0", config.bindAddress)
        assertEquals(9876, config.port)
        assertEquals("test-token", config.token)
        assertEquals("server-data", config.dataDir.fileName.toString())
    }
}
