package com.opentermx.mgmt

import com.opentermx.netparsers.Vendor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdapterRegistryTest {

    /** Adaptador de prueba con disponibilidad configurable. */
    private class FakeAdapter(
        override val method: MgmtMethod,
        private val avail: AdapterAvailability,
    ) : ManagementAdapter {
        override fun isAvailable() = avail
        override fun describe(device: DeviceRef) = AdapterDescriptor(
            method,
            listOf(
                OperationDescriptor("${method.name.lowercase()}.read", "r", OperationKind.READ),
                OperationDescriptor("${method.name.lowercase()}.write", "w", OperationKind.WRITE),
            ),
        )
        override suspend fun executeRead(device: DeviceRef, op: ReadOperation) =
            AdapterResult.Success(mapOf("ok" to true))
        override suspend fun proposeWrite(device: DeviceRef, op: WriteOperation) =
            ProposalTicket(method, op.id, device.hostname, "payload", op.rationale)
    }

    @Test
    fun `resuelve adaptador por metodo y reporta disponibilidad`() {
        val reg = AdapterRegistry(
            listOf(
                FakeAdapter(MgmtMethod.REST_API, AdapterAvailability.Available),
                FakeAdapter(MgmtMethod.NETMIKO, AdapterAvailability.Unavailable("bridge no instalado")),
            ),
        )
        assertTrue(reg.availability(MgmtMethod.REST_API).isAvailable)
        val netmiko = reg.availability(MgmtMethod.NETMIKO)
        assertFalse(netmiko.isAvailable)
        assertEquals("bridge no instalado", netmiko.reasonOrNull)
    }

    @Test
    fun `metodo sin adaptador registrado no esta disponible, con motivo accionable`() {
        val reg = AdapterRegistry(emptyList())
        val a = reg.availability(MgmtMethod.ANSIBLE)
        assertFalse(a.isAvailable)
        assertTrue(a.reasonOrNull!!.contains("sin adaptador"))
        assertNull(reg.forMethod(MgmtMethod.ANSIBLE))
    }

    @Test
    fun `el descriptor separa lecturas de escrituras (base del validador server-side)`() {
        val d = FakeAdapter(MgmtMethod.REST_API, AdapterAvailability.Available)
            .describe(DeviceRef(1, "h", Vendor.UNKNOWN))
        assertEquals(listOf("rest_api.read"), d.readOperations.map { it.id })
        assertEquals(listOf("rest_api.write"), d.writeOperations.map { it.id })
        assertEquals(OperationKind.READ, d.operation("rest_api.read")?.kind)
    }
}
