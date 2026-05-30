package com.bg7yoz.ft8cn.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.bg7yoz.ft8cn.MainActivity;
import com.bg7yoz.ft8cn.R;

/**
 * Foreground service to keep audio recording and FT8 decoding active in background.
 *
 * [IMPORTANT] This service:
 * 1. Shows persistent notification (required by Android 8+)
 * 2. Acquires PARTIAL_WAKE_LOCK to prevent CPU sleep during FT8 slots
 * 3. Must be started before HamRecorder.startRecord() for reliable background operation
 *
 * @author R3BCK + qwen.ai
 * @date 2026-05-14
 * Comments in Russian, ASCII only in code.
 */
public class RecordingForegroundService extends Service {
    private static final String TAG = "RecordingFgService";
    private static final String CHANNEL_ID = "FT8CN_Recording_Channel";
    private static final int NOTIFICATION_ID = 1001;

    private final IBinder binder = new LocalBinder();

    // [NEW] WakeLock to keep CPU awake during FT8 slots (15-sec cycles)
    private PowerManager.WakeLock wakeLock;

    public class LocalBinder extends Binder {
        public RecordingForegroundService getService() {
            return RecordingForegroundService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        // [NEW] Acquire WakeLock on service creation
        acquireWakeLock();
        Log.d(TAG, "Service created, WakeLock acquired");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // [FIX] Intent to bring existing MainActivity to front (preserve state)
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // [NEW] PendingIntent flags for compatibility with Android 6+
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, pendingIntentFlags
        );

        // Start foreground with persistent notification
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FT8CN Running")
                .setContentText("Audio recording active for FT8 and rig operation")
                .setSmallIcon(R.drawable.ic_baseline_mic_48)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)  // [NEW] Link tap to open app
                .setAutoCancel(false)              // [NEW] Keep notification after tap
                .build();

        startForeground(NOTIFICATION_ID, notification);
        Log.d(TAG, "Service started in foreground");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // [NEW] Release WakeLock on service destroy
        releaseWakeLock();
        stopForeground(true);
        Log.d(TAG, "Service destroyed, WakeLock released");
    }

    // [NEW] Acquire partial WakeLock (CPU stays on, screen can off)
    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ft8cn:recording_wakelock"  // Tag for debugging
            );
            wakeLock.setReferenceCounted(false);  // Don't use ref counting (simpler)

            // Acquire for max 15 minutes (FT8 cycles are 15 sec, so this is renewable)
            // If service stays alive, we can re-acquire in onStartCommand if needed
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(15 * 60 * 1000L);  // 15 minutes
                Log.d(TAG, "WakeLock acquired (15 min timeout)");
            }
        }
    }

    // [NEW] Release WakeLock safely
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released");
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "FT8CN Recording Service",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Keeps audio recording active in background");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    // Static helpers to start/stop from anywhere
    public static void start(Context context) {
        Intent intent = new Intent(context, RecordingForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
        Log.d(TAG, "start() called");
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, RecordingForegroundService.class);
        context.stopService(intent);
        Log.d(TAG, "stop() called");
    }
}