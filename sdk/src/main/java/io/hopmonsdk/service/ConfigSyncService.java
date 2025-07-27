package io.hopmonsdk.service;

import android.app.AlarmManager;
import android.app.IntentService;
import android.content.Intent;

import io.hopmonsdk.Hopmn;
import io.hopmonsdk.receiver.ConfigSyncAlarmTrigger;
import io.hopmonsdk.util.LogUtils;

public class ConfigSyncService extends IntentService {

    public static final String TAG = ConfigSyncService.class.getSimpleName();

    /**
     * Creates an IntentService.  Invoked by your subclass's constructor.
     */
    public ConfigSyncService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        LogUtils.d(TAG, "scheduleExactAlarm");
        ConfigSyncAlarmTrigger.scheduleExactAlarm(ConfigSyncService.this, (AlarmManager) getSystemService(ALARM_SERVICE), Hopmn.getInstance(this).getDelayMillis());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LogUtils.d(TAG, "cancelAlarm");
        ConfigSyncAlarmTrigger.cancelAlarm(this, (AlarmManager) getSystemService(ALARM_SERVICE));
    }

}