package com.frizovdanya.pixpet

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.SystemClock

class PetNotificationListenerService : NotificationListenerService() {
    private var lastEventKey: String? = null
    private var lastEventAt = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!PetPrefs.load(this).reactNotifications) return
        if (sbn.packageName !in TELEGRAM_PACKAGES) return
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val extras = notification.extras ?: return

        val text = buildString(400) {
            append(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
            append(' ')
            append(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            append(' ')
            append(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty())
        }.lowercase().take(400)
        if (text.isBlank()) return

        val mood = when {
            LOVE.containsMatchIn(text) -> "love"
            LAUGH.containsMatchIn(text) -> "laugh"
            HELLO.containsMatchIn(text) -> "hi"
            else -> "mail"
        }
        val key = "${sbn.packageName}:${sbn.id}:${sbn.tag.orEmpty()}"
        val now = SystemClock.elapsedRealtime()
        if (key == lastEventKey && now - lastEventAt < 1_200L) return
        lastEventKey = key
        lastEventAt = now
        sendBroadcast(
            android.content.Intent(GUPetContract.ACTION_REACTION)
                .setPackage(packageName)
                .putExtra(GUPetContract.EXTRA_MOOD, mood),
        )
    }

    companion object {
        private val TELEGRAM_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.plus",
            "org.thunderdog.challegram",
        )
        private val LOVE = Regex("❤|♥|💕|💖|😘|😍|🥰|\\bлюблю\\b|\\blove\\b")
        private val LAUGH = Regex("(?:ха){2,}|(?:хе){2,}|(?:хи){2,}|\\bлол\\b|\\blol\\b|😂|🤣")
        private val HELLO = Regex("\\b(?:привет\\w*|здравствуй\\w*|хай|салют|hello|hi|hey)\\b")
    }
}
