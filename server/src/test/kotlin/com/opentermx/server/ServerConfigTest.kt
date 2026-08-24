package com.opentermx.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class ServerConfigTest {
    @TempDir
    lateinit var tempDir: Path

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

    @Test
    fun `writable mode requires configured agent security`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromEnvironment(mapOf("OPENTERMX_READ_ONLY" to "false"))
        }
        val config = ServerConfig.fromEnvironment(
            mapOf(
                "OPENTERMX_READ_ONLY" to "false",
                "OPENTERMX_AGENT_TOKEN" to "0123456789abcdef",
            ),
        )
        assertEquals(false, config.readOnly)
    }

    @Test
    fun `reads service and database secrets from files`() {
        val mcp = tempDir.resolve("mcp").also { it.writeText("mcp-secret\n") }
        val agent = tempDir.resolve("agent").also { it.writeText("0123456789abcdef\n") }
        val database = tempDir.resolve("database").also { it.writeText("database-secret\n") }

        val config = ServerConfig.fromEnvironment(
            mapOf(
                "OPENTERMX_BIND" to "0.0.0.0",
                "OPENTERMX_MCP_TOKEN_FILE" to mcp.toString(),
                "OPENTERMX_AGENT_TOKEN_FILE" to agent.toString(),
                "OPENTERMX_DB_HOST" to "postgres",
                "OPENTERMX_DB_PASSWORD_FILE" to database.toString(),
            ),
        )

        assertEquals("mcp-secret", config.token)
        assertEquals("0123456789abcdef", config.agentToken)
        assertEquals("postgres", config.database?.host)
        assertEquals("database-secret", config.database?.password)
        assertEquals(true, config.database?.required)
    }

    @Test
    fun `database host requires a password secret`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromEnvironment(mapOf("OPENTERMX_DB_HOST" to "postgres"))
        }
    }

    @Test
    fun `accepts individual agent credential file and rejects ambiguous auth`() {
        val credentials = tempDir.resolve("agents.properties").also {
            it.writeText("noc-01=0123456789abcdef\n")
        }
        val config = ServerConfig.fromEnvironment(
            mapOf("OPENTERMX_AGENT_CREDENTIALS_FILE" to credentials.toString()),
        )
        assertEquals(credentials.toAbsolutePath(), config.agentCredentialsFile)

        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromEnvironment(
                mapOf(
                    "OPENTERMX_AGENT_TOKEN" to "0123456789abcdef",
                    "OPENTERMX_AGENT_CREDENTIALS_FILE" to credentials.toString(),
                ),
            )
        }
    }
}
