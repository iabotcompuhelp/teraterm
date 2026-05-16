package com.opentermx.mcp.handlers

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListSessionsHandlerTest {

    private val handler = ListSessionsHandler()

    @AfterEach
    fun cleanup() = TestFixtures.unregisterAll()

    @Test
    fun `lista vacía cuando no hay sesiones`() = runBlocking {
        val result = handler.invoke(emptyMap())
        @Suppress("UNCHECKED_CAST")
        val sessions = result["sessions"] as List<Map<String, Any?>>
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `agrega metadata y vendor detectado del buffer`() = runBlocking {
        TestFixtures.registerSession(
            idValue = "s1",
            protocol = "SSH",
            host = "router.lab",
            port = 22,
            username = "admin",
            buffer = listOf("Cisco IOS Software, C2960 Software"),
        )
        TestFixtures.registerSession(
            idValue = "s2",
            protocol = "Telnet",
            host = "mk.lab",
            port = 23,
            buffer = listOf("MikroTik RouterOS 7.10"),
        )
        val result = handler.invoke(emptyMap())
        @Suppress("UNCHECKED_CAST")
        val sessions = result["sessions"] as List<Map<String, Any?>>
        assertEquals(2, sessions.size)
        val byId = sessions.associateBy { it["sessionId"] as String }
        assertEquals("SSH", byId["s1"]!!["protocol"])
        assertEquals("Cisco IOS", byId["s1"]!!["vendor"])
        assertEquals("MikroTik RouterOS", byId["s2"]!!["vendor"])
    }
}