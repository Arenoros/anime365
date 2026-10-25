package io.github.arenoros.anime365

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import io.github.arenoros.anime365.core.data.di.ApplicationScope
import io.github.arenoros.anime365.core.datastore.SettingsStore
import io.github.arenoros.anime365.feature.library.notifications.NewEpisodesCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

// Срок кэша постеров — docs/roadmap.md, M1.
private const val POSTER_CACHE_BYTES = 200L * 1024 * 1024

@HiltAndroidApp
class Anime365Application :
    Application(),
    SingletonImageLoader.Factory,
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var settings: SettingsStore

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        NewEpisodesCheck.schedule(this)
        scope.launch {
            settings.settings.map { it.adultContent }.distinctUntilChanged().collect { allowed ->
                if (!allowed) NewEpisodesCheck.hideAdultNotifications(this@Anime365Application)
            }
        }
        // Переключатель в Настройках действует сразу, без перезапуска.
        // Сбой раньше, чем прочитаны настройки, в отчёт не попадёт.
        if (CrashReports.available) {
            scope.launch(Dispatchers.Main) {
                settings.settings.map { it.crashReports }.distinctUntilChanged().collect { enabled ->
                    if (enabled) CrashReports.enable(this@Anime365Application) else CrashReports.disable()
                }
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .crossfade(true)
            .diskCache {
                DiskCache
                    .Builder()
                    .directory(context.cacheDir.resolve("posters"))
                    .maxSizeBytes(POSTER_CACHE_BYTES)
                    .build()
            }.build()
}
