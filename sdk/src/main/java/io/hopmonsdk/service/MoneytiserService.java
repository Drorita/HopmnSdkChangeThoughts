package io.hopmonsdk.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.hopmon.BuildConfig;
import io.hopmon.R;
import io.hopmonsdk.Hopmn;
import io.hopmonsdk.data.DataStore;
import io.hopmonsdk.event.NetworkStateChanged;
import io.hopmonsdk.job.ConfigSyncJob;
import io.hopmonsdk.util.LogUtils;

public class MoneytiserService extends Service{

    public static final String CHANNEL_ID = "ForegroundServiceChannel";

    private static final String TAG = MoneytiserService.class.getSimpleName();

    private ConfigSyncJob configSyncJob;

    private HttpManager httpManager;

    private final IBinder binder = new ProxyServiceBinder();

    public class ProxyServiceBinder extends Binder {
        public MoneytiserService getService() {
            return MoneytiserService.this;
        }
    }


    @Override
    public void onCreate() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

        try {
            Hopmn instance = Hopmn.getInstance(this);
            if (instance != null) {
                httpManager = instance.getHttpManager();
                configSyncJob = new ConfigSyncJob(this, pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG));
                LogUtils.d(TAG, "Service was created");
            }
        }
        catch(Exception ex){
            LogUtils.e(TAG, "Failed to getInstance on MoneytiserService onCreate: ", ex);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogUtils.d(TAG, "onStartCommand called");
        super.onStartCommand(intent, flags, startId);
        try {
            if(intent.getBooleanExtra(Hopmn.NEED_FOREGROUND_KEY, false)&& Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
                LogUtils.d(TAG, "foreground Service - create notification");
                showNotification();
            }
        //    boolean useJobScheduler = intent.getBooleanExtra(Hopmn.ASYNC_JOB_SCHEDULER_KEY, false);
            DataStore ds = Hopmn.getInstance(this).getDataStore();
            String uid = ds.get(getString(R.string.hopmon_uid_key));
            String cc = ds.get(getString(R.string.hopmon_country_key));
            if (uid != null && cc != null) {
                LogUtils.d(TAG, "The device is already registered");
                configSyncJob.schedule(uid, ds.get(getString(R.string.hopmon_country_key)));
               /* if (!useJobScheduler) {
                    pullSyncJob.schedule(uid, ds.get(getString(R.string.hopmon_publisher_key)),ds.get(getString(R.string.hopmon_country_key)));
                }*/
            }
            else {
                register();
            }
        }
        catch(Exception ex)
        {
            LogUtils.e(TAG, "OnStartCommand failed! Error = %s ", ex.getMessage());
        }

        return Service.START_STICKY;
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private String createNotificationChannel(String channelId, String channelName){
        NotificationChannel chan = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_NONE);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager service = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        service.createNotificationChannel(chan);
        return channelId;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void showNotification() {
        DataStore ds = DataStore.getInstance(this);
        String appName = ds.get("APPNAME", "Hopmn");
        int icon = ds.getInt("ICON", R.drawable.ic_android_notify);
        String notify_message = ds.get("MESSAGE", "Background service is running");

        String chanId = createNotificationChannel("popa_service_chan", appName);

        Intent stopSelf = new Intent(this, MoneytiserService.class);

        stopSelf.setAction("ACTION_NOTIFY_CLICKED");

        PendingIntent pStopSelf = PendingIntent
                .getService(this, 0, stopSelf
                        , PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_CANCEL_CURRENT);

        Notification.Action action =
                new Notification.Action.Builder(
                        0, "Close", pStopSelf
                ).build();

        Notification notification =
                new Notification.Builder(this, chanId)
                        .setContentTitle(appName)
                        .setContentText(notify_message)
                        .setSmallIcon(icon)
                        .setContentIntent(pStopSelf)
                        .addAction(action)
                        .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }
    }


    /**
     * Method that will be called when someone posts an event NetworkStateChanged.
     *
     * @param event the intercepted event
     */
    public void onNetworkStateChanged(NetworkStateChanged event) {
        if (!event.isInternetConnected()) {
            LogUtils.d(TAG, "Connected to network!");
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
         return binder;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        LogUtils.d(TAG, "Task removed");
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        LogUtils.d(TAG, "Detected low memory");
    }

    @Override
    public void onDestroy() {

        super.onDestroy();
        if (httpManager != null) {
            httpManager.stop();
        }
        if (configSyncJob != null) {
            configSyncJob.shutdown();
        }
        LogUtils.w(TAG, "Service was stopped");

   /*     if(!Hopmn.userStopRequest) {
            Intent restartService = new Intent(Hopmn.class.getCanonicalName());
            restartService.putExtra(Hopmn.NEED_RESTART_KEY, true);
            LocalBroadcastManager.getInstance(this).sendBroadcast(restartService);
            LogUtils.w(TAG, "Service was restarted");
        }*/
    }

    public int getRequestsCounts() {
        return configSyncJob != null ? configSyncJob.getRequestsCounts() : 1;
    }

    public List<Throwable> getErrors() {
        return configSyncJob != null ? configSyncJob.getErrors() : new ArrayList<Throwable>();
    }

    public boolean isRunning() {
        return configSyncJob != null && configSyncJob.isRunning();
    }

    public long getProxyUpTime(TimeUnit unit) {
        return configSyncJob != null ? configSyncJob.getUpTime(unit) : 0;
    }

    private void register() {
        try {
            final Hopmn acp = Hopmn.getInstance(this);
            final String usr = UUID.randomUUID().toString();
            final String ver = BuildConfig.VERSION_NAME;
            final String pub = acp.getPublisher();
            //   acp.getDataStore().set(getString(R.string.hopmon_publisher_key), pub);
            String cat = acp.getCategory();
            String regUrl = acp.isSecure() ? acp.getSecureRegUrl() : acp.getRegUrl();
            String regEndpoint = acp.getRegEndpoint();
            if (!regUrl.endsWith("/") && !regEndpoint.startsWith("/")) {
                regUrl += "/";
            }
            // Request a string response from the provided URL.
            String url = regUrl.replace(Hopmn.PUBLISHER_PLACE_HOLDER, pub) + regEndpoint
                    .replace(Hopmn.PUBLISHER_PLACE_HOLDER, pub)
                    .replace(Hopmn.UID_PLACE_HOLDER, usr)
                    .replace(Hopmn.CID_PLACE_HOLDER, cat)
                    .replace(Hopmn.VER_PLACE_HOLDER, ver);
            LogUtils.d(TAG, "Trying to register the device %s using url %s", usr, url);
            StringRequest request = new StringRequest(Request.Method.POST, url,
                    new Response.Listener<String>() {
                        @Override
                        public void onResponse(String response) {
                            LogUtils.d(TAG, String.format("Device %s successfully registered", usr));
                            if (response.matches("[a-zA-Z]*")) {
                                acp.getDataStore().set(getString(R.string.hopmon_country_key), response);
                                acp.setCountry(response);
                            }
                            acp.getDataStore().set(getString(R.string.hopmon_uid_key), usr);
                            acp.setUid(usr);
                            configSyncJob.schedule(usr, response);
                        }
                    },
                    new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            LogUtils.e(TAG, "An error occurred while calling registration service:", error.getCause());
                        }
                    }
            );
            httpManager.addToRequestQueue(request);
        }
        catch(Exception ex)
        {
            LogUtils.e(TAG, "Failed on registration: ", ex.toString());
        }
    }

}
