package com.opentermx.mcp.handlers

import com.opentermx.ai.audit.AiAuditEntry
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.safety.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ReadAuditLogHandlerTest {

    @TempDir
    lateinit var tmp: Path

    private fun handler(log: AiAuditLog): ReadAuditLogHandler = ReadAuditLogHandler(auditLog = log)

    private fun sampleEntry(t: Long, sid: String, cmd: String = "show version"): AiAuditEntry =
        AiAuditEntry(
            timestampMillis = t, sessionId = sid, host = "1.2.3.4", vendor = "Cisco IOS",
            prompt = "test", commands = listOf(cmd),
            commandRisks = listOf(RiskLevel.SAFE),
            executedCount = 1, skippedCount = 0, failedCount = 0, rejected = false,
            outputTail = "enable secret 5 ABCD ",
        )

    @Test
    fun `archivo inexistente devuelve entries vacios`() = runBlocking {
        val log = AiAuditLog(tmp.resolve("none.csv"))
        val result = handler(log).invoke(emptyMap())
        @Suppress("UNCHECKED_CAST")
        val entries = result["entries"] as List<Map<String, Any?>>
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `entries son devueltas con redaction aplicada al outputTail`() = runBlocking {
        val log = AiAuditLog(tmp.resolve("a.csv"))
        log.append(sampleEntry(t = 1_000L, sid = "session-1"))
        val result = handler(log).invoke(mapOf("sessionId" to "session-1"))
        @Suppress("UNCHECKED_CAST")
        val entries = result["entries"] as List<Map<String, Any?>>
        assertEquals(1, entries.size)
        val tail = entries[0]["outputTail"] as String
        assertTrue(tail.contains("********"), "outputTail debe estar redactado")
        assertFalse(tail.contains("ABCD"))
    }

    @Test
    fun `filtro por sessionId prefix funciona`() = runBlocking {
        val log = AiAuditLog(tmp.resolve("b.csv"))
        log.append(sampleEntry(t = 1_000L, sid = "session-1"))
        log.append(sampleEntry(t = 2_000L, sid = "session-2"))
        log.append(sampleEntry(t = 3_000L, sid = "session-1#abc"))

        val result = handler(log).invoke(mapOf("sessionId" to "session-1"))
        @Suppress("UNCHECKED_CAST")
        val entries = result["entries"] as List<Map<String, Any?>>
        assertEquals(2, entries.size, "matchea session-1 y session-1#abc")
        // Ordenado por timestamp desc.
        assertEquals(3_000L, entries[0]["timestampMillis"])
    }

    @Test
    fun `limit respeta el cap`() = runBlocking {
        val log = AiAuditLog(tmp.resolve("c.csv"))
        repeat(10) { log.append(sampleEntry(t = it * 1_000L, sid = "s")) }
        val result = handler(log).invoke(mapOf("limit" to 3))
        @Suppress("UNCHECKED_CAST")
        val entries = result["entries"] as List<Map<String, Any?>>
        assertEquals(3, entries.size)
    }
}