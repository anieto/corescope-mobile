package org.nodescope.android.core.network

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.nodescope.android.BuildConfig
import org.nodescope.android.MainActivity
import org.nodescope.android.R
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PushPlatform {
    const val relayOrigin = BuildConfig.PUSH_RELAY_ORIGIN
    const val environment = BuildConfig.PUSH_ENVIRONMENT
    const val CHANNEL = "watched_nodes"
    val enabled = listOf(BuildConfig.PUSH_FIREBASE_APP_ID, BuildConfig.PUSH_FIREBASE_API_KEY,
        BuildConfig.PUSH_FIREBASE_SENDER, BuildConfig.PUSH_FIREBASE_PROJECT).all { it.isNotBlank() }

    @Synchronized
    fun initialize(context: Context) {
        if (!enabled) return
        if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
            FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.PUSH_FIREBASE_APP_ID)
                .setApiKey(BuildConfig.PUSH_FIREBASE_API_KEY)
                .setGcmSenderId(BuildConfig.PUSH_FIREBASE_SENDER)
                .setProjectId(BuildConfig.PUSH_FIREBASE_PROJECT).build())
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.staging_push_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun canNotify(context: Context): Boolean = enabled &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    suspend fun token(context: Context): String = suspendCancellableCoroutine { continuation ->
        initialize(context)
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (task.isSuccessful && !task.result.isNullOrBlank()) continuation.resume(task.result)
            else continuation.resumeWithException(IllegalStateException("FCM registration failed"))
        }
    }
}

class StagingMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        callbacks.launch { StagingPush.attempt { StagingPush.get(applicationContext).refresh(applicationContext, token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val route = StagingPushRoute.parse(message.data::get) ?: return
        if (!PushPlatform.canNotify(this) || !StagingPush.get(this).ownsRoute(route)) return
        val alert = message.notification ?: return
        val eventId = message.data["event_id"] ?: return
        val tap = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            for (key in listOf("version", "analyzer_id", "subscription_id", "node_pubkey")) {
                putExtra(key, message.data[key])
            }
        }
        val pending = PendingIntent.getActivity(this, eventId.hashCode(), tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, PushPlatform.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nodescope)
            .setColor(ContextCompat.getColor(this, R.color.staging_push_accent))
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // canNotify checks Android 13 permission.
        NotificationManagerCompat.from(this).notify(eventId, 0, notification)
    }

    companion object {
        private val callbacks = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
