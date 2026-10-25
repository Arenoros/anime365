package io.github.arenoros.anime365.feature.account

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.github.arenoros.anime365.core.datastore.AppSettings
import io.github.arenoros.anime365.core.testing.captureScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun defaults() = settings("settings", AppSettings())

    @Test
    fun adultEnabled() = settings("settings_adult", AppSettings(adultContent = true))

    @Test
    fun changedLargeFont() =
        settings("settings_font200", AppSettings(newEpisodeNotifications = false, wifiHeight = 2160), 2f)

    private fun settings(
        name: String,
        settings: AppSettings,
        fontScale: Float = 1f,
    ) = compose.captureScreen(name, fontScale) {
        SettingsScreen(settings, onUpdate = {}, onEnableNotifications = {}, onBack = {}, crashReportsAvailable = true)
    }
}
