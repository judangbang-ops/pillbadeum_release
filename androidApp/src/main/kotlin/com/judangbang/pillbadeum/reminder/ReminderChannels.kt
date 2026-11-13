package com.judangbang.pillbadeum.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * The two notification channels (TASK-025). Their ids are shared with the app's notification guidance
 * (TASK-026, SPEC-028), which reads [DOSES] to tell whether the person switched reminders off in the
 * system settings. Names stay here as constants, not in strings.xml.
 *
 * Android fixes a channel's importance and sound when it is first created, so these must not change.
 */
object ReminderChannels {
    /** Dose reminders: heads-up with sound and vibration (SCR-003). */
    const val DOSES: String = "dose_reminders"

    /**
     * "복용 시간이에요" on the lock screen while names are hidden, the restore notice and the result of a
     * button: no sound and no vibration, so the lock-screen notice adds no second sound (SPEC-013, N-13).
     * Default importance rather than low, so lock screens that hide silent notifications still show it.
     */
    const val NOTICES: String = "app_notices"

    private const val DOSES_NAME = "복용 알림"
    private const val DOSES_DESCRIPTION = "약 먹을 때를 알려요"
    private const val NOTICES_NAME = "안내 (소리 없음)"
    private const val NOTICES_DESCRIPTION = "잠금 화면의 '복용 시간이에요', 백업 복원 안내, 알림 버튼을 누른 결과예요"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val doses = NotificationChannel(DOSES, DOSES_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = DOSES_DESCRIPTION
            enableVibration(true)
        }
        val notices = NotificationChannel(NOTICES, NOTICES_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = NOTICES_DESCRIPTION
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannels(listOf(doses, notices))
    }
}
