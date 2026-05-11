package com.opentermx.ai.audit

import com.opentermx.ai.safety.RiskLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AiAuditLogTest {

    @Test
    fun writesHeaderOnFirstAppendAndAppendsSubsequentRows(@TempDir dir: Path) {
        val file = dir.resolve("audit.csv")
        val log = AiAuditLog(file)
        log.append(
            AiAuditEntry(
                timestampMillis = 1_700_000_000_000L,
                sessionId = "s-1",
                host = "router1",
                vendor = "Cisco IOS",
                prompt = "configura ip 10.0.0.1",
                commands = listOf("configure terminal", "interface Gi0/1", " ip address 10.0.0.1 255.255.255.0", "end"),
                commandRisks = listOf(RiskLevel.CONFIG, RiskLevel.CONFIG, RiskLevel.CONFIG, RiskLevel.CONFIG),
                executedCount = 4, skippedCount = 0, failedCount = 0, rejected = false,
                outputTail = "Building config…\nOK",
            )
        )
        log.append(
            AiAuditEntry(
                timestampMillis = 1_700_000_010_000L,
                sessionId = "s-1",
                host = "router1",
                vendor = "Cisco IOS",
                prompt = "borra config",
                commands = listOf("write erase"),
                commandRisks = listOf(RiskLevel.DANGEROUS),
                executedCount = 0, skippedCount = 0, failedCount = 0, rejected = true,
            )
        )
        val lines = Files.readAllLines(file)
        assertEquals(3, lines.size)
        assertEquals(AiAuditLog.HEADER, lines[0])
        assertTrue(lines[1].contains("router1"))
        assertTrue(lines[1].contains("CONFIG=4"))
        assertTrue(lines[2].contains("DANGEROUS=1"))
        assertTrue(lines[2].endsWith(","))
    }

    @Test
    fun escapesCommasAndQuotesInPromptAndCommands(@TempDir dir: Path) {
        val file = dir.resolve("audit.csv")
        val log = AiAuditLog(file)
        log.append(
            AiAuditEntry(
                timestampMillis = 1_700_000_000_000L,
                sessionId = "s-2",
                host = "h",
                vendor = "X",
                prompt = "set, value, with \"quotes\"",
                commands = listOf("echo 'hello, world'"),
                commandRisks = listOf(RiskLevel.SAFE),
                executedCount = 1, skippedCount = 0, failedCount = 0, rejected = false,
            )
        )
        val line = Files.readAllLines(file).last()
        assertTrue("\"set, value, with \"\"quotes\"\"\"" in line)
        assertTrue("\"echo 'hello, world'\"" in line)
    }
}
