package dev.sysmoon.ferry.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

class MqttBridgeTest {
    @Test fun defaultEntityIdsAreDeviceNamePlusEntityName() {
        assertEquals("update.kitchen_shield_ferry", MqttBridge.defaultEntityId("update", "Kitchen Shield", "Ferry"))
        assertEquals("button.kitchen_shield_pause_play", MqttBridge.defaultEntityId("button", "Kitchen Shield", "Pause / play"))
        assertEquals("sensor.snug_tv_programme_progress", MqttBridge.defaultEntityId("sensor", "Snug TV", "Programme progress"))
    }
}
