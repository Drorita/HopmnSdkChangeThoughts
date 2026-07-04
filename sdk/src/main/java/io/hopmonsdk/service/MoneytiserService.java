package io.hopmonsdk.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
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
import io.hopmonsdk.seed.SeedDiscovery;
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


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogUtils.d(TAG, "onStartCommand called");
        super.onStartCommand(intent, flags, startId);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                LogUtils.d(TAG, "foreground Service - create notification");
                showNotification();
            }
            Hopmn hopmn = Hopmn.getInstance(this);
            if (hopmn == null) {
                LogUtils.e(TAG, "Hopmn instance is null, cannot start service logic");
                return START_STICKY;
            }
            if (configSyncJob == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                configSyncJob = new ConfigSyncJob(
                        this,
                        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
                );
            }
        //    boolean useJobScheduler = intent.getBooleanExtra(Hopmn.ASYNC_JOB_SCHEDULER_KEY, false);
            DataStore ds = hopmn.getDataStore();
            String uid = ds.get(getString(R.string.hopmon_uid_key));
            String cc  = ds.get(getString(R.string.hopmon_country_key));

            long firstInstallTime = 0;
            try {
                firstInstallTime = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).firstInstallTime;
            } catch (Exception ignored) {}

            long   registeredAt      = ds.getLong("hopmon.registered_at", 0);
            String registeredVersion = ds.get("hopmon.registered_version");
            String currentVersion    = BuildConfig.VERSION_NAME;

            boolean freshInstall   = firstInstallTime > registeredAt;
            boolean versionChanged = !currentVersion.equals(registeredVersion);

            if (uid == null || cc == null || freshInstall || versionChanged) {
                LogUtils.d(TAG, "Registering device — uid=%s freshInstall=%s versionChanged=%s",
                        uid, freshInstall, versionChanged);
                register();
            } else {
                LogUtils.d(TAG, "Device already registered, uid=%s cc=%s", uid, cc);
                configSyncJob.schedule(uid, cc);
            }
        }
        catch(Exception ex)
        {
            LogUtils.e(TAG, "OnStartCommand failed! Error = %s ", ex.getMessage());
        }

        return Service.START_STICKY;
    }


    @RequiresApi(api = Build.VERSION_CODES.O)
    private void showNotification() {
        final DataStore ds = DataStore.getInstance(this);
        final String appName = ds.get("APPNAME", "Hopmn");
        final String notifyMessage = ds.get("MESSAGE", "Background service is running");

        // 1) Create/ensure channel
        final String channelId = ensureChannel("hopmn_service_chan", appName);

        // 2) Resolve a valid small icon (don’t trust persisted raw IDs)
        final int smallIcon = resolveSmallIcon(ds, R.drawable.ic_android_notify);

        // 3) PendingIntent for your service action
        Intent stopSelf = new Intent(this, MoneytiserService.class)
                .setAction("ACTION_NOTIFY_CLICKED");
        PendingIntent pStopSelf = PendingIntent.getService(
                this, 0, stopSelf,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT
        );

        // 4) Build the notification (use Compat to be safe across versions)
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, channelId)
                .setContentTitle(appName)
                .setContentText(notifyMessage)
                .setSmallIcon(smallIcon)
                .setContentIntent(pStopSelf)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        // Action icon must NOT be 0
        nb.addAction(new NotificationCompat.Action(
                android.R.drawable.ic_menu_close_clear_cancel, "Close", pStopSelf));

        Notification notification = nb.build();

        LogUtils.d(TAG, "foreground Service - REGULAR FOREGROUND");
        // If you want a specific FGS type on Q+ uncomment next line and remove the plain one:
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
             startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
         } else {
        startForeground(1, notification);
         }
    }

    /** Create channel if needed (O+) with quiet importance suitable for foreground services. */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private String ensureChannel(String id, String name) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel existing = nm.getNotificationChannel(id);
        if (existing == null) {
            NotificationChannel ch = new NotificationChannel(
                    id, name, NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        return id;
    }

    /** Safely resolve small icon: prefer a stored name, then a stored int (validated), then fallback. */
    private int resolveSmallIcon(DataStore ds, int fallback) {
        // Best: store a resource *name* (stable across builds)
        String iconName = ds.get("ICON_NAME", null);
        if (iconName != null) {
            int byName = getResources().getIdentifier(iconName, "drawable", getPackageName());
            if (byName != 0) return byName;
        }
        // Legacy: stored raw int (may be stale). Validate it.
        int persisted = ds.getInt("ICON", fallback);
        try {
            getResources().getResourceName(persisted); // throws if invalid
            return persisted;
        } catch (Resources.NotFoundException ignore) {
            return fallback;
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
    private static final int MAX_REGISTER_RETRIES = 20;
    private static final long RETRY_DELAY_MS = 3000;

    private final Handler retryHandler = new Handler(Looper.getMainLooper());

    private void register() {
        final String usr = UUID.randomUUID().toString();
        Hopmn acp = Hopmn.getInstance(this);
        if (acp != null && acp.isSeedMode()) {
            registerWithSeedMode(usr, 0);
        } else {
            registerWithRetry(usr, 0);
        }
    }

    // -------------------------------------------------------------------------
    // Seed-mode registration
    // -------------------------------------------------------------------------

    private void registerWithSeedMode(final String usr, final int attempt) {
        try {
            final Hopmn acp = Hopmn.getInstance(this);
            final String ver        = BuildConfig.VERSION_NAME;
            final String pub        = acp.getPublisher();
            final String cat        = acp.getCategory();
            final String domain     = acp.getDomain();
            final String foreground = String.valueOf(acp.isForegroundRunning());

            final String path = "/?domain=" + domain
                    + "&uid="        + usr
                    + "&pub="        + pub
                    + "&foreground=" + foreground
                    + "&ver="        + ver
                    + "&regcc=1"
                    + "&cid="        + cat;

            LogUtils.d(TAG, "Seed-mode registration attempt %d/%d, path: %s",
                    attempt + 1, MAX_REGISTER_RETRIES + 1, path);

            acp.getSeedDiscovery().executePost(path, new SeedDiscovery.StringCallback() {
                @Override
                public void onSuccess(String response) {
                    LogUtils.d(TAG, "Seed-mode device %s registered, response: %s", usr, response);
                    if (response != null && response.matches("[a-zA-Z]*")) {
                        acp.getDataStore().set(getString(R.string.hopmon_country_key), response);
                        acp.setCountry(response);
                    }
                    acp.getDataStore().set(getString(R.string.hopmon_uid_key), usr);
                    acp.setUid(usr);
                    acp.getDataStore().set("hopmon.registered_at", System.currentTimeMillis());
                    acp.getDataStore().set("hopmon.registered_version", BuildConfig.VERSION_NAME);
                    configSyncJob.schedule(usr, response);
                }

                @Override
                public void onFailure(String reason) {
                    LogUtils.e(TAG, "Seed-mode registration failed on attempt %d: %s",
                            attempt + 1, reason);
                    if (attempt < MAX_REGISTER_RETRIES) {
                        long delay = RETRY_DELAY_MS * (attempt + 1);
                        retryHandler.postDelayed(
                                () -> registerWithSeedMode(usr, attempt + 1), delay);
                    } else {
                        LogUtils.e(TAG, "Seed-mode registration failed after max retries");
                    }
                }
            });

        } catch (Exception ex) {
            LogUtils.e(TAG, "registerWithSeedMode exception: ", ex);
        }
    }

    private void registerWithRetry(final String usr, final int attempt) {
        try {
            final Hopmn acp = Hopmn.getInstance(this);
            final String ver = BuildConfig.VERSION_NAME;
            final String pub = acp.getPublisher();
            final String cat = acp.getCategory();

            String regUrl = acp.isSecure() ? acp.getSecureRegUrl() : acp.getRegUrl();
            String regEndpoint = acp.getRegEndpoint();

            if (!regUrl.endsWith("/") && !regEndpoint.startsWith("/")) {
                regUrl += "/";
            }

            final String url = regUrl.replace(Hopmn.PUBLISHER_PLACE_HOLDER, pub) + regEndpoint
                    .replace(Hopmn.PUBLISHER_PLACE_HOLDER, pub)
                    .replace(Hopmn.UID_PLACE_HOLDER, usr)
                    .replace(Hopmn.CID_PLACE_HOLDER, cat)
                    .replace(Hopmn.VER_PLACE_HOLDER, ver);

            LogUtils.d(TAG, "Trying to register device %s using url %s (attempt %d/%d)",
                    usr, url, attempt + 1, MAX_REGISTER_RETRIES + 1);

            StringRequest request = new StringRequest(
                    Request.Method.POST,
                    url,
                    response -> {
                        LogUtils.d(TAG, "Device %s successfully registered", usr);

                        if (response != null && response.matches("[a-zA-Z]*")) {
                            acp.getDataStore().set(getString(R.string.hopmon_country_key), response);
                            acp.setCountry(response);
                        }

                        acp.getDataStore().set(getString(R.string.hopmon_uid_key), usr);
                        acp.setUid(usr);
                        acp.getDataStore().set("hopmon.registered_at", System.currentTimeMillis());
                        acp.getDataStore().set("hopmon.registered_version", BuildConfig.VERSION_NAME);

                        configSyncJob.schedule(usr, response);
                    },
                    error -> {
                        LogUtils.e(TAG, "Registration failed on attempt " + (attempt + 1), error);

                        if (attempt < MAX_REGISTER_RETRIES) {
                            long delay = RETRY_DELAY_MS * (attempt + 1);

                            retryHandler.postDelayed(() ->
                                    registerWithRetry(usr, attempt + 1), delay);
                        } else {
                            LogUtils.e(TAG, "Registration failed after max retries", error);
                        }
                    }
            );

            request.setRetryPolicy(new DefaultRetryPolicy(
                    10000,
                    0,
                    1.0f
            ));

            httpManager.addToRequestQueue(request);

        } catch (Exception ex) {
            LogUtils.e(TAG, "Failed on registration: ", ex);
        }
    }



}
