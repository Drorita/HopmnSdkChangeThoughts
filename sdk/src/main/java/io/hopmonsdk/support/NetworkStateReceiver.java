package io.hopmonsdk.support;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

import io.hopmonsdk.job.ConfigSyncJob;
import io.hopmonsdk.util.LogUtils;

public class NetworkStateReceiver extends BroadcastReceiver {
    ConfigSyncJob subscriber = null;
    public static IntentFilter getIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
//        intentFilter.addAction("android.net.wifi.WIFI_STATE_CHANGED");
        return intentFilter;
    }
    public static final String TAG = NetworkStateReceiver.class.getSimpleName();

    // post event if there is no Internet connection
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action!= null && action.equals(android.net.ConnectivityManager.CONNECTIVITY_ACTION)) {
            if (isNetworkAvailable(context)) {
                LogUtils.w(TAG, "reconnect to network");
                if (subscriber != null) {
                    subscriber.reschedule();
                }
            }
        }

    }

    public boolean isNetworkAvailable(Context context) {
        if(context == null)  return false;


        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager != null) {


            if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
                if (capabilities != null) {
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return true;
                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return true;
                    }  else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)){
                        return true;
                    }
                }
            }
            else {

                try {
                    NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
                    if (activeNetworkInfo != null && activeNetworkInfo.isConnected()) {
                        return true;
                    }
                } catch (Exception e) {
                    LogUtils.i(TAG, "" + e.getMessage());
                }
            }
        }
        LogUtils.i(TAG,"Network is available : FALSE ");
        return false;
    }

    public void setSubscriber(ConfigSyncJob job)
    {
        subscriber = job;
    }
}
