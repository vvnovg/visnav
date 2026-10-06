package io.visnav.app.obd

import io.visnav.core.ElmState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObdSettingsTest {
    @Test fun parseKind() {
        assertEquals(ObdSettings.OFF, ObdSettings.kind(null))
        assertEquals(ObdSettings.OFF, ObdSettings.kind("usb"))
        assertEquals(ObdSettings.SPP, ObdSettings.kind("spp"))
        assertEquals(ObdSettings.BLE, ObdSettings.kind("ble"))
        assertEquals(ObdSettings.OFF, ObdSettings.kind("off"))
    }

    @Test fun parseSettings() {
        assertEquals(ObdSettings(), ObdSettings.parse(null, null, null))
        assertEquals(ObdSettings("spp", "AA:BB", "OBDII", "spp"), ObdSettings.parse("spp", "AA:BB", "OBDII"))
        // Пустой адрес — устройство не выбрано.
        assertEquals(ObdSettings("ble", null, null), ObdSettings.parse("ble", "", "x"))
        assertFalse(ObdSettings().enabled)
        assertTrue(ObdSettings("ble").enabled)
    }

    @Test fun withKindKeepsAdapterOnOffAndDropsItOnTransportChange() {
        val spp = ObdSettings("spp", "AA:BB", "OBDII", addrKind = "spp")
        val off = spp.withKind("off")
        assertEquals(ObdSettings("off", "AA:BB", "OBDII", addrKind = "spp"), off)
        assertEquals(spp, off.withKind("spp"))
        assertEquals(ObdSettings("ble"), spp.withKind("ble"))
        assertEquals(ObdSettings("ble"), off.withKind("ble"))
        // Старые настройки без obd_addr_kind: адаптер относится к текущему виду.
        assertEquals("spp", ObdSettings.parse("spp", "AA:BB", "x").addrKind)
        assertEquals("ble", ObdSettings.parse("off", "AA:BB", "x", "ble").addrKind)
    }

    @Test fun kindCyclesAndLabels() {
        assertEquals("spp", ObdSettings.next("off"))
        assertEquals("ble", ObdSettings.next("spp"))
        assertEquals("off", ObdSettings.next("ble"))
        assertEquals("выкл", obdKindLabel("off"))
        assertEquals("Bluetooth", obdKindLabel("spp"))
        assertEquals("BLE", obdKindLabel("ble"))
    }

    @Test fun bleNameFilter() {
        for (n in listOf("OBDII", "obd2", "V-LINK", "IOS-Vlink", "vgate iCar", "OBDLink CX", "My OBD")) {
            assertTrue(isObdName(n), n)
        }
        for (n in listOf(null, "", "Mi Band", "JBL Flip", "V LINK")) assertFalse(isObdName(n), n.toString())
    }

    @Test fun permissionsPerKindAndSdk() {
        val connect = "android.permission.BLUETOOTH_CONNECT"
        val scan = "android.permission.BLUETOOTH_SCAN"
        assertEquals(emptyList(), obdPermissions("off", 34))
        assertEquals(listOf(connect), obdPermissions("spp", 31))
        assertEquals(listOf(connect, scan), obdPermissions("ble", 33))
        assertEquals(emptyList(), obdPermissions("spp", 30))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), obdPermissions("ble", 29))
    }

    @Test fun factorKeysAndSave() {
        assertEquals("obd_k_AA:BB", obdKKey("AA:BB"))
        assertEquals("obd_kslow_AA:BB", obdKSlowKey("AA:BB"))
        // Только после включения правила (b): до того kSlow не подтверждена, сохранённое не трогаем.
        assertNull(factorsToSave(false, 0.97, 0.98))
        assertNull(factorsToSave(true, null, 0.98))
        assertNull(factorsToSave(true, 0.97, null))
        assertNull(factorsToSave(true, Double.NaN, 0.98))
        assertEquals(0.97 to 0.98, factorsToSave(true, 0.97, 0.98))
    }

    @Test fun restoredFactor() {
        assertNull(restoredFactor(Float.NaN))
        assertNull(restoredFactor(null))
        assertNull(restoredFactor(0f))
        assertEquals(0.97f.toDouble(), restoredFactor(0.97f))
    }

    @Test fun stateLabels() {
        assertEquals("опрос", obdStateLabel(ElmState.POLLING, null))
        assertEquals("ошибка: timeout", obdStateLabel(ElmState.ERROR, "timeout"))
        assertEquals("переподключение через 2000 ms", obdStateLabel(ElmState.RECONNECT_WAIT, "2000 ms"))
        assertEquals("нет данных: NO DATA", obdStateLabel(ElmState.NO_DATA, "NO DATA"))
    }

    @Test fun obdLineDuringRecording() {
        assertEquals("OBD: —", obdLine(null))
        assertEquals("OBD: подключение", obdLine(ObdUi("подключение")))
        assertEquals("OBD: опрос · 62 км/ч · RTT 48 мс", obdLine(ObdUi("опрос", 62, 47.6, "OBDII")))
    }
}
