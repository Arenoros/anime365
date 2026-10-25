package io.github.arenoros.anime365.feature.library.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.arenoros.anime365.core.data.repository.LibraryRepository
import io.github.arenoros.anime365.core.data.repository.SeriesRepository
import io.github.arenoros.anime365.core.datastore.SettingsStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Проверяет аниме со Статусом «Смотрю» и сообщает о Новых Эпизодах (docs/screens.md, «Уведомления»).
 * Уведомления выключены в Настройках — Новые Эпизоды всё равно записываются: их видно на табе «Список».
 */
@HiltWorker
internal class NewEpisodesWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val library: LibraryRepository,
        private val series: SeriesRepository,
        private val notifier: NewEpisodesNotifier,
        private val settings: SettingsStore,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            library.watching().forEach { id ->
                // Недоступная страница одного аниме не мешает проверить остальные.
                if (series.refresh(id, force = true).isFailure) return@forEach
                val loaded = series.series(id).first() ?: return@forEach
                val fresh = library.recordNewEpisodes(loaded)
                val current = settings.settings.first()
                val visible = current.adultContent || !loaded.summary.isHentai
                if (current.newEpisodeNotifications && visible && fresh.isNotEmpty()) {
                    notifier.notify(loaded.summary, fresh)
                }
            }
            return Result.success()
        }
    }

/** Расписание проверки Новых Эпизодов: раз в 6 часов, когда есть сеть (docs/roadmap.md, M3). */
object NewEpisodesCheck {
    /** Контент 18+ выключили: уже показанные уведомления о нём снимаются. */
    fun hideAdultNotifications(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag == ADULT_NOTIFICATION_TAG }.forEach {
            manager.cancel(it.tag, it.id)
        }
    }

    private const val WORK_NAME = "new_episodes"
    private const val PERIOD_HOURS = 6L

    /** Вызывать при каждом запуске: уже стоящее расписание не сбрасывается. */
    fun schedule(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<NewEpisodesWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
