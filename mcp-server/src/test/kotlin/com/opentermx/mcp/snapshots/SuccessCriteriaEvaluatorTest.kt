package com.opentermx.mcp.snapshots

import com.opentermx.mcp.operation.SuccessCriterion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuccessCriteriaEvaluatorTest {

    private fun snap(content: String) = Snapshot(
        id = "x", operationId = null, sessionId = "s", deviceAlias = null,
        snapshotType = "running_config", timestampMillis = 0L,
        contentHash = Snapshot.hashOf(content), content = content,
    )

    @Test
    fun `command_output_contains pass cuando el pattern matchea`() {
        val s = snap("router# show ip ospf neighbor\nNeighbor ID FULL/DR\n10.0.0.1 FULL")
        val c = SuccessCriterion(
            type = "command_output_contains", command = "show ip ospf neighbor", pattern = "FULL",
        )
        val res = SuccessCriteriaEvaluator.evaluate(s, listOf(c))
        assertEquals(SuccessCriteriaEvaluator.Overall.ALL_PASS, res.overall)
        assertEquals(SuccessCriteriaEvaluator.Status.PASS, res.results.first().status)
    }

    @Test
    fun `command_output_contains fail cuando el pattern NO matchea`() {
        val s = snap("router# show ip ospf neighbor\n(no neighbors)")
        val c = SuccessCriterion(
            type = "command_output_contains", command = "show ip ospf neighbor", pattern = "FULL",
        )
        val res = SuccessCriteriaEvaluator.evaluate(s, listOf(c))
        assertEquals(SuccessCriteriaEvaluator.Status.FAIL, res.results.first().status)
    }

    @Test
    fun `command_output_contains fail si el snapshot no contiene rastro del comando`() {
        val s = snap("hostname router-a")
        val c = SuccessCriterion(
            type = "command_output_contains", command = "show foo", pattern = "x",
        )
        val res = SuccessCriteriaEvaluator.evaluate(s, listOf(c))
        assertEquals(SuccessCriteriaEvaluator.Status.FAIL, res.results.first().status)
        assertTrue(res.results.first().message.contains("no contiene rastro"))
    }

    @Test
    fun `no_interface_down PASS cuando ninguna interfaz está down`() {
        val s = snap("interface Gi0/1 is up, line protocol is up\ninterface Gi0/2 is up, line protocol is up")
        val res = SuccessCriteriaEvaluator.evaluate(s, listOf(SuccessCriterion(type = "no_interface_down")))
        assertEquals(SuccessCriteriaEvaluator.Status.PASS, res.results.first().status)
    }

    @Test
    fun `no_interface_down FAIL cuando hay alguna down`() {
        val s = snap("interface Gi0/1 is up, line protocol is up\ninterface Gi0/2 is administratively down")
        val res = SuccessCriteriaEvaluator.evaluate(s, listOf(SuccessCriterion(type = "no_interface_down")))
        assertEquals(SuccessCriteriaEvaluator.Status.FAIL, res.results.first().status)
        assertTrue(res.results.first().message.contains("Gi0/2"))
    }

    @Test
    fun `route_exists PASS busca el destination literal`() {
        val s = snap("S    10.0.0.0/24 [1/0] via 192.168.1.1\nC    192.168.1.0/24 is directly connected")
        val res = SuccessCriteriaEvaluator.evaluate(s,
            listOf(SuccessCriterion(type = "route_exists", destination = "10.0.0.0/24")))
        assertEquals(SuccessCriteriaEvaluator.Status.PASS, res.results.first().status)
    }

    @Test
    fun `type desconocido se marca WARN sin afectar otros criterios`() {
        val s = snap("ok")
        val res = SuccessCriteriaEvaluator.evaluate(s, listOf(
            SuccessCriterion(type = "i_made_this_up"),
            SuccessCriterion(type = "command_output_contains", pattern = "ok"),
        ))
        assertEquals(SuccessCriteriaEvaluator.Status.WARN, res.results[0].status)
        assertEquals(SuccessCriteriaEvaluator.Status.PASS, res.results[1].status)
        assertEquals(SuccessCriteriaEvaluator.Overall.PARTIAL, res.overall)
    }

    @Test
    fun `criteria vacío produce ALL_PASS trivial`() {
        val s = snap("anything")
        val res = SuccessCriteriaEvaluator.evaluate(s, emptyList())
        assertEquals(SuccessCriteriaEvaluator.Overall.ALL_PASS, res.overall)
        assertTrue(res.results.isEmpty())
    }
}
