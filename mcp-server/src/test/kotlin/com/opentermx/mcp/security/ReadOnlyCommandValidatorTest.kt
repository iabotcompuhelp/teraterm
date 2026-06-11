package com.opentermx.mcp.security

import com.opentermx.ai.context.Vendor
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadOnlyCommandValidatorTest {

    private val validator = ReadOnlyCommandValidator.embedded()

    private fun allowed(cmd: String, vendor: Vendor) {
        val v = validator.validate(cmd, vendor)
        assertTrue(v is ReadOnlyValidation.Allowed, "esperaba Allowed para `$cmd` ($vendor), vino $v")
    }

    private fun rejected(cmd: String, vendor: Vendor) {
        val v = validator.validate(cmd, vendor)
        assertTrue(v is ReadOnlyValidation.Rejected, "esperaba Rejected para `$cmd` ($vendor)")
    }

    // --------------------------------------------------- positivos (criterio: >= 15)

    @Test
    fun `comandos de lectura tipicos pasan la whitelist`() {
        allowed("show version", Vendor.CISCO_IOS)                                  // 1
        allowed("show ip interface brief", Vendor.CISCO_IOS_XE)                    // 2
        allowed("show running-config | include ntp", Vendor.CISCO_IOS)             // 3
        allowed("show run | begin interface", Vendor.CISCO_IOS)                    // 4
        allowed("show log | i error", Vendor.CISCO_IOS)                            // 5
        allowed("ping 10.0.0.1", Vendor.CISCO_IOS)                                 // 6
        allowed("traceroute 8.8.8.8", Vendor.CISCO_IOS)                            // 7
        allowed("dir flash:", Vendor.CISCO_IOS)                                    // 8
        allowed("terminal length 0", Vendor.CISCO_IOS)                             // 9
        allowed("show interface ethernet 1/1", Vendor.CISCO_NX_OS)                 // 10
        allowed("show route | count", Vendor.JUNIPER_JUNOS)                        // 11
        allowed("show configuration | display set | no-more", Vendor.JUNIPER_JUNOS) // 12
        allowed("display interface GigabitEthernet0/0/1", Vendor.HUAWEI_VRP)       // 13
        allowed("tracert 10.1.1.1", Vendor.HUAWEI_VRP)                             // 14
        allowed("screen-length 0 temporary", Vendor.HUAWEI_VRP)                    // 15
        allowed("show interfaces brief", Vendor.ARUBA_OS)                          // 16
        allowed("display lldp neighbor-info", Vendor.ARUBA_OS)                     // 17
        allowed("get system status", Vendor.FORTINET_FORTIOS)                      // 18
        allowed("get system interface | grep port1", Vendor.FORTINET_FORTIOS)      // 19
        allowed("diagnose sys top", Vendor.FORTINET_FORTIOS)                       // 20
        allowed("diagnose hardware deviceinfo nic port1", Vendor.FORTINET_FORTIOS) // 21
        allowed("/interface print stats without-paging", Vendor.MIKROTIK_ROUTEROS) // 22
        allowed("/ip route print", Vendor.MIKROTIK_ROUTEROS)                       // 23
        allowed("/system resource print", Vendor.MIKROTIK_ROUTEROS)                // 24
    }

    // --------------------------------------------------- negativos (criterio: >= 15)

    @Test
    fun `comandos mutativos o no whitelisteados se rechazan`() {
        rejected("configure terminal", Vendor.CISCO_IOS)                           // 1
        rejected("reload", Vendor.CISCO_IOS)                                       // 2
        rejected("erase startup-config", Vendor.CISCO_IOS)                         // 3
        rejected("copy running-config startup-config", Vendor.CISCO_IOS)           // 4
        rejected("write memory", Vendor.CISCO_IOS)                                 // 5
        rejected("clear counters", Vendor.CISCO_IOS)                               // 6
        rejected("terminal monitor", Vendor.CISCO_IOS)                             // 7: solo length/width
        rejected("ping", Vendor.CISCO_IOS)                                         // 8: ping sin destino (modo interactivo)
        rejected("request system reboot", Vendor.JUNIPER_JUNOS)                    // 9
        rejected("save", Vendor.HUAWEI_VRP)                                        // 10
        rejected("reset saved-configuration", Vendor.HUAWEI_VRP)                   // 11
        rejected("execute factoryreset", Vendor.FORTINET_FORTIOS)                  // 12
        rejected("execute ping 10.0.0.1", Vendor.FORTINET_FORTIOS)                 // 13: execute no whitelisteado
        rejected("/interface set 0 disabled=yes", Vendor.MIKROTIK_ROUTEROS)        // 14: sin `print`
        rejected("ip route print", Vendor.MIKROTIK_ROUTEROS)                       // 15: sin slash canónico
        rejected("showme version", Vendor.CISCO_IOS)                               // 16: boundary de palabra
    }

    @Test
    fun `inyecciones y metacaracteres se rechazan`() {
        rejected("show version\nreload", Vendor.CISCO_IOS)                         // 17: newline embebido
        rejected("show version\r\nerase startup-config", Vendor.CISCO_IOS)         // 18
        rejected("show version ; reload", Vendor.CISCO_IOS)                        // 19
        rejected("show version && erase startup-config", Vendor.CISCO_IOS)         // 20
        rejected("show version || reload", Vendor.CISCO_IOS)                       // 21
        rejected("show run | redirect tftp://10.0.0.5/conf", Vendor.CISCO_IOS)     // 22
        rejected("show run | tee flash:copia.txt", Vendor.CISCO_IOS)               // 23
        rejected("show configuration | save /var/tmp/out", Vendor.JUNIPER_JUNOS)   // 24
        rejected("show tech-support > /tmp/exfil", Vendor.CISCO_IOS)               // 25
        rejected("show log >> out.txt", Vendor.CISCO_IOS)                          // 26
        rejected("show `reload`", Vendor.CISCO_IOS)                                // 27
        rejected("show \$(reload)", Vendor.CISCO_IOS)                              // 28
        rejected("show version |", Vendor.CISCO_IOS)                               // 29: pipe vacío
    }

    @Test
    fun `limites estructurales`() {
        rejected("s", Vendor.CISCO_IOS)                                            // < 2 chars
        rejected("", Vendor.CISCO_IOS)
        rejected("   ", Vendor.CISCO_IOS)
        rejected("show " + "a".repeat(510), Vendor.CISCO_IOS)                      // > 512 chars
        allowed("show " + "a".repeat(500), Vendor.CISCO_IOS)                       // <= 512 pasa
    }

    @Test
    fun `vendor UNKNOWN se rechaza siempre — sin whitelist no se adivina`() {
        rejected("show version", Vendor.UNKNOWN)
        rejected("display version", Vendor.UNKNOWN)
        rejected("ping 8.8.8.8", Vendor.UNKNOWN)
    }

    @Test
    fun `deny-list gana sobre la whitelist`() {
        rejected("show tech-support", Vendor.CISCO_IOS)
        rejected("diagnose sys kill 11 1234", Vendor.FORTINET_FORTIOS)
        rejected("display diagnostic-information", Vendor.HUAWEI_VRP)
    }

    // --------------------------------------------------- carga YAML

    @Test
    fun `whitelist custom desde YAML reemplaza a la embebida`() {
        val custom = ReadOnlyCommandValidator.fromYaml(
            """
            version: 1
            vendors:
              CISCO_IOS:
                - '^show clock$'
            """.trimIndent()
        )
        assertTrue(custom.validate("show clock", Vendor.CISCO_IOS) is ReadOnlyValidation.Allowed)
        assertTrue(custom.validate("show version", Vendor.CISCO_IOS) is ReadOnlyValidation.Rejected)
        // Vendor sin entrada en el YAML custom => rechazado.
        assertTrue(custom.validate("display version", Vendor.HUAWEI_VRP) is ReadOnlyValidation.Rejected)
    }

    @Test
    fun `regex sin anclar se ancla solo — show por substring no es agujero`() {
        val custom = ReadOnlyCommandValidator.fromYaml(
            """
            vendors:
              CISCO_IOS:
                - 'show\s+version'
            """.trimIndent()
        )
        assertTrue(custom.validate("show version", Vendor.CISCO_IOS) is ReadOnlyValidation.Allowed)
        assertTrue(custom.validate("reload now show version", Vendor.CISCO_IOS) is ReadOnlyValidation.Rejected)
    }

    @Test
    fun `YAML invalido lanza para que el caller degrade al embebido`() {
        assertThrows(Exception::class.java) {
            ReadOnlyCommandValidator.fromYaml("vendors:\n  NO_EXISTE:\n    - '^show .*$'")
        }
        assertThrows(Exception::class.java) {
            ReadOnlyCommandValidator.fromYaml("version: 1\ndeny: []")
        }
    }
}
