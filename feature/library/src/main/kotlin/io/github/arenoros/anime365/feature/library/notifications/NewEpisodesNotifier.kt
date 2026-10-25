package io.github.arenoros.anime365.feature.library.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arenoros.anime365.core.designsystem.icon.KadrIcons
import io.github.arenoros.anime365.core.model.Episode
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.feature.library.R
import javax.inject.Inject

private const val CHANNEL_ID = "new_episodes"
internal const val ADULT_NOTIFICATION_TAG = "adult_new_episodes"
private const val EXTRA_SERIES_ID = "io.github.arenoros.anime365.extra.SERIES_ID"
private const val EXTRA_EPISODE_ID = "io.github.arenoros.anime365.extra.EPISODE_ID"

/** Касание уведомления о Новом Эпизоде: что открыть в Плеере. */
data class EpisodeLink(
    val seriesId: Int,
    val episodeId: Int,
) {
    companion object {
        fun from(intent: Intent?): EpisodeLink? {
            val seriesId = intent?.getIntExtra(EXTRA_SERIES_ID, 0) ?: 0
            val episodeId = intent?.getIntExtra(EXTRA_EPISODE_ID, 0) ?: 0
            return if (seriesId > 0 && episodeId > 0) EpisodeLink(seriesId, episodeId) else null
        }
    }
}

/** Одно уведомление на аниме: «8 серия · Провожающая в последний путь Фрирен», касание — первый Новый Эпизод. */
internal class NewEpisodesNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun notify(
            series: SeriesSummary,
            episodes: List<Episode>,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = NotificationManagerCompat.from(context)
            manager.createNotificationChannel(
                NotificationChannelCompat
                    .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.feature_library_channel_name))
                    .setDescription(context.getString(R.string.feature_library_channel_description))
                    .build(),
            )
            val first = episodes.first()
            val title =
                if (episodes.size == 1) {
                    first.label
                } else {
                    context.getString(R.string.feature_library_notification_more, first.label, episodes.size - 1)
                }
            // Своё Activity фича не знает: открываем то, что запускается с рабочего стола.
            val intent =
                context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.putExtra(EXTRA_SERIES_ID, series.id)
                    ?.putExtra(EXTRA_EPISODE_ID, first.id) ?: return
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(KadrIcons.PlayCircleOutline)
                    .setContentTitle(title)
                    .setContentText(series.title)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            series.id,
                            intent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    ).setAutoCancel(true)
                    .build()
            manager.notify(if (series.isHentai) ADULT_NOTIFICATION_TAG else null, series.id, notification)
        }
    }
