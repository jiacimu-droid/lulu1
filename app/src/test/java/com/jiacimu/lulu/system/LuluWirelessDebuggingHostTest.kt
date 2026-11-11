package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Test

class LuluWirelessDebuggingHostTest {
    @Test fun systemPairingIpTakesPriorityOverLoopback() {
        assertEquals(
            listOf("192.168.1.36", "192.168.1.42", "127.0.0.1"),
            LuluLocalAdbAddress.candidateHosts(
                "192.168.1.36", listOf("192.168.1.42"), "127.0.0.1"),
        )
    }

    @Test fun newWifiComesBeforeOldPairingIp() {
        assertEquals(
            listOf("192.168.8.9", "192.168.8.2", "127.0.0.1"),
            LuluLocalAdbAddress.candidateHosts(
                null, listOf("192.168.8.9"), "192.168.8.2"),
        )
    }

    @Test fun duplicateWifiIpsAreRemovedAndLoopbackIsLastFallback() {
        assertEquals(
            listOf("192.168.1.5", "127.0.0.1"),
            LuluLocalAdbAddress.candidateHosts(
                "", listOf("192.168.1.5", "192.168.1.5"), "192.168.1.5"),
        )
        assertEquals(
            listOf("127.0.0.1"),
            LuluLocalAdbAddress.candidateHosts(null, emptyList(), null),
        )
    }
}
