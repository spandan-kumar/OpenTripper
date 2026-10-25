package io.opentripper

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.opentripper.core.MapsNotification
import io.opentripper.core.MapsParser

class MapsObserver : NotificationListenerService() {
    override fun onListenerConnected() {
        AppState.observerConnected = true
        AppState.notifyChanged()
        AppState.requestMapsSnapshot = { activeNotifications?.forEach(::onNotificationPosted); Unit }
        AppState.requestMapsSnapshot?.invoke()
    }
    override fun onListenerDisconnected() {
        AppState.requestMapsSnapshot = null
        AppState.observerConnected = false
        AppState.onMapsDisconnect?.invoke()
        AppState.notifyChanged()
    }
    override fun onDestroy() {
        AppState.requestMapsSnapshot = null
        AppState.observerConnected = false
        AppState.onMapsDisconnect?.invoke()
        AppState.notifyChanged()
        super.onDestroy()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MapsParser.PACKAGE) return
        val extras = sbn.notification.extras
        val textKeys = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)
        val lines = textKeys.mapNotNull { extras.getCharSequence(it)?.toString() } +
            (extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ?: emptyList())
        AppState.onMapsPost?.invoke(MapsNotification(sbn.packageName, sbn.key, sbn.isOngoing, lines))
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == MapsParser.PACKAGE) AppState.onMapsRemove?.invoke(sbn.key)
    }
}
