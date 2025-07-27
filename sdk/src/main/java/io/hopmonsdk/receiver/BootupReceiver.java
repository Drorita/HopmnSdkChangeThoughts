package io.hopmonsdk.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import io.hopmon.R;
import io.hopmonsdk.data.DataStore;
import io.hopmonsdk.service.MoneytiserService;

public class BootupReceiver extends BroadcastReceiver {

    public static final String NEED_FOREGROUND_KEY = "need_forground";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("BootupReceiver", "BootupReceiver intent received: " + intent.toString());
        Intent installProxy = new Intent(context, MoneytiserService.class);
        DataStore ds = new DataStore(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ds.is(context.getString(R.string.hopmon_foreground))){
            Log.d("BootupReceiver", "BootupReceiver start foreground service");
            installProxy.putExtra(NEED_FOREGROUND_KEY, true);
            context.startForegroundService(installProxy);
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
        {
            Log.d("BootupReceiver", "BootupReceiver start background service");
            context.startService(installProxy);
        }

    }
}

