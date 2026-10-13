package dev.sort.clickhouse

import com.intellij.openapi.components.service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import dev.sort.clickhouse.pipe.ClickHousePipeConfigurable
import dev.sort.clickhouse.pipe.ClickHousePipeSettings

class ClickHousePipeSettingsTest : ClickHousePlatformTestCase() {
    fun testProjectSwitchIsStoredInTheWorkspace() {
        val state = requireNotNull(ClickHousePipeSettings::class.java.getAnnotation(State::class.java))
        assertEquals(StoragePathMacros.WORKSPACE_FILE, state.storages.single().value)
    }

    fun testSettingsPageAppliesTheProjectSwitch() {
        val settings = project.service<ClickHousePipeSettings>()
        val previous = settings.enabled
        val page = ClickHousePipeConfigurable(project)
        try {
            assertFalse(settings.enabled)
            val box = UIUtil.findComponentsOfType(page.createComponent(), JBCheckBox::class.java).single()
            assertFalse(box.isSelected)
            box.isSelected = true
            assertTrue(page.isModified)
            page.apply()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertTrue(settings.enabled)
            page.reset()
            assertTrue(box.isSelected)
            assertFalse(page.isModified)
        } finally {
            page.disposeUIResources()
            settings.enabled = previous
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }
}
