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
    fun neutralizesSpreadsheetFormulaInjectionFromUntrustedDeviceOutput(@TempDir dir: Path) {
        val file = dir.resolve("audit.csv")
        val log = AiAuditLog(file)
        // Banner/output de un equipo malicioso: campos que arrancan con caracteres de fórmula.
        log.append(
            AiAuditEntry(
                timestampMillis = 1_700_000_000_000L,
                sessionId = "s-evil",
                host = "=HYPERLINK(\"http://evil\",\"x\")",
                vendor = "X",
                prompt = "+cmd|'/c calc'",
                commands = listOf("@SUM(1+1)", "show version"),
                commandRisks = listOf(RiskLevel.SAFE, RiskLevel.SAFE),
                executedCount = 2, skippedCount = 0, failedCount = 0, rejected = false,
                outputTail = "-2+3",
            )
        )
        val row = Files.readAllLines(file).last()
        // Ningún campo del CSV arranca con un carácter de fórmula sin neutralizar:
        val fields = parseFieldsForTest(row)
        for (f in fields) {
            assertTrue(
                f.isEmpty() || f.first() !in charArrayOf('=', '+', '-', '@').toList(),
                "campo `$f` arranca con carácter de fórmula sin neutralizar",
            )
        }
        // El contenido sigue presente (solo prefijado con ' dentro del campo).
        assertTrue(row.contains("'=HYPERLINK"))
        assertTrue(row.contains("'@SUM(1+1)"))
        assertTrue(row.contains("'-2+3"))
    }

    /** Split CSV que respeta comillas, para inspeccionar campos en el test. */
    private fun parseFieldsForTest(row: String): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < row.length) {
            val c = row[i]
            if (q) {
                if (c == '"') { if (i + 1 < row.length && row[i + 1] == '"') { sb.append('"'); i++ } else q = false }
                else sb.append(c)
            } else when (c) {
                ',' -> { out += sb.toString(); sb.clear() }
                '"' -> q = true
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString(); return out
    }

    @Test
    fun concurrentAppendsKeepExactlyOneHeaderAndNoCorruptedRows(@TempDir dir: Path) {
        val file = dir.resolve("audit.csv")
        val log = AiAuditLog(file)
        val threads = 8
        val perThread = 50
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val start = java.util.concurrent.CountDownLatch(1)
        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(perThread) { i ->
                    log.append(
                        AiAuditEntry(
                            timestampMillis = 1_700_000_000_000L + t * 1000 + i,
                            sessionId = "t$t-$i",
                            host = "h$t",
                            vendor = "V",
                            prompt = "p$t-$i con, coma",
                            commands = listOf("show version", "show run"),
                            commandRisks = listOf(RiskLevel.SAFE, RiskLevel.SAFE),
                            executedCount = 2, skippedCount = 0, failedCount = 0, rejected = false,
                            outputTail = "tail $t-$i",
                        )
                    )
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))

        val lines = Files.readAllLines(file)
        // Exactamente 1 header + N filas, sin duplicar el header ni perder/corromper filas.
        assertEquals(threads * perThread + 1, lines.size, "líneas totales")
        assertEquals(1, lines.count { it == AiAuditLog.HEADER }, "header único")
        // Toda fila (salvo el header) arranca con el timestamp ISO — ninguna intercalada.
        val tsRegex = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3},""")
        for (line in lines.drop(1)) {
            assertTrue(tsRegex.containsMatchIn(line), "fila corrupta/intercalada: `$line`")
        }
        // read() recupera las N entradas.
        assertEquals(threads * perThread, log.read(limit = Int.MAX_VALUE).size)
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
