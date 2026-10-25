package io.github.arenoros.anime365.core.datastore

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataStoreSettingsStoreTest {
    @Test
    fun startsWithDefaultsAndKeepsEachSettingInItsOwnKey() =
        runTest {
            val store = DataStoreSettingsStore(ApplicationProvider.getApplicationContext())
            assertEquals(AppSettings(), store.settings.first())

            // Одна запись: на Windows DataStore не может заменить уже записанный файл, тест падал бы не по делу.
            store.updateSettings {
                it.copy(
                    newEpisodeNotifications = false,
                    wifiHeight = 480,
                    mobileHeight = 360,
                    downloadWifiOnly = false,
                    downloadHeight = 1080,
                    adultContent = true,
                )
            }

            val expected =
                AppSettings(
                    newEpisodeNotifications = false,
                    wifiHeight = 480,
                    mobileHeight = 360,
                    downloadWifiOnly = false,
                    downloadHeight = 1080,
                    adultContent = true,
                )
            assertEquals(expected, store.settings.first())
        }
}
