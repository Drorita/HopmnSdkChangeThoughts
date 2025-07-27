package io.hopmonsdk.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;

public class ConfigSyncAlarmTrigger extends BroadcastReceiver {

    public static final String TAG = ConfigSyncAlarmTrigger.class.getSimpleName();

    @Override
    public void onReceive(final Context context, Intent intent) {
        scheduleExactAlarm(context, (AlarmManager) context.getSystemService(Context.ALARM_SERVICE), 5*60*1000);

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wl.acquire();

        Handler handler = new Handler();
        Runnable periodicUpdate = new Runnable() {
            @Override
            public void run() {
                // whatever you want to do
            }
        };

        handler.post(periodicUpdate);
        wl.release();
    }

    public static void scheduleExactAlarm(Context context, AlarmManager alarms, long interval) {
        long refreshInterval = interval;
        Intent i = new Intent(context, ConfigSyncAlarmTrigger.class);
        PendingIntent pi;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
            pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE);
        }
        else {
            pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE);
        }
        if(Build.VERSION.SDK_INT >=Build.VERSION_CODES.KITKAT) {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + refreshInterval - SystemClock.elapsedRealtime() % 1000, pi);
        }
    }

    public static void cancelAlarm(Context context, AlarmManager alarms) {
        PendingIntent pi;
        Intent i = new Intent(context, ConfigSyncAlarmTrigger.class);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
            pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE);
        }
        else {
            pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE);
        }
        alarms.cancel(pi);
    }
}