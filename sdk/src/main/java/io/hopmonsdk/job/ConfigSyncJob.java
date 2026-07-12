package io.hopmonsdk.job;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;


import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.hopmon.BuildConfig;
import io.hopmonsdk.Hopmn;
import io.hopmonsdk.HopmnSrv;
import io.hopmonsdk.seed.SeedDiscovery;
import io.hopmonsdk.service.HttpManager;
import io.hopmonsdk.support.ConfigManager;
import io.hopmonsdk.support.NetworkStateReceiver;
import io.hopmonsdk.task.ProxyAsyncTask;
import io.hopmonsdk.util.LogUtils;

public class ConfigSyncJob implements Runnable {

    public static final String TAG = ConfigSyncJob.class.getSimpleName();

    private Context context;

    private ConfigManager confManager;

    private HttpManager httpManager;

    private ProxyAsyncTask proxyTask;

    private List<Throwable> errors;

    private final Handler handler = new Handler();

    private PowerManager.WakeLock wakeLock;

    private final long retryDelay = 2000;

    private final int maxRetries = 10;

    private int requestsCounts = 0;

    private int failedAttempts = 0;

    private String uid;
    
    private String country;

    private static final long DELAY_IN_CASE_NO_CONNECTIVITY  = 5*60*1000; // 5 minutes

    private NetworkStateReceiver connectivityChangedBroadcastReceiver;
    private boolean scheduled = false;
    private boolean shutdown = false;
    public ConfigSyncJob(Context ctx, PowerManager.WakeLock wl) {
        try {
            Hopmn acp = Hopmn.getInstance(ctx);
            context = ctx;
            wakeLock = wl;
            confManager = acp.getConfigManager();
            httpManager = acp.getHttpManager();
            errors = new ArrayList<>(maxRetries);
        }
        catch (Exception ex)
        {
            LogUtils.e(TAG, "create ConfigSyncJob failed! Error = %s ", ex.getMessage());
        }

        // register broadcast receiver for network change
  //      connectivityChangedBroadcastReceiver = new NetworkStateReceiver();
  //      connectivityChangedBroadcastReceiver.setSubscriber(this);
  //      context.registerReceiver(connectivityChangedBroadcastReceiver, NetworkStateReceiver.getIntentFilter());

    }

    public void schedule(String userId, String cc) {
        uid = userId;
        country = cc;
        if (shutdown) {
            LogUtils.w(TAG, "ConfigSyncJob is shutdown - ignoring schedule");
            return;
        }

        if (scheduled) {
            LogUtils.d(TAG, "ConfigSyncJob already scheduled - skipping duplicate schedule");
            return;
        }
        scheduled = true;

        handler.removeCallbacks(this);
        handler.post(this);
        LogUtils.d(TAG, "Scheduled configuration synchronization job");

    }

    public void reschedule()
    {
        if((proxyTask != null && proxyTask.isRunning()))
        {
            LogUtils.d(TAG, "ReScheduled configuration synchronization job");
            handler.removeCallbacks(ConfigSyncJob.this);
            handler.post(ConfigSyncJob.this);
        }
        else
        {
            schedule(uid,country);
        }
    }

    @Override
    public void run() {
        if (shutdown || !scheduled) {
            LogUtils.d(TAG, "ConfigSyncJob run ignored. shutdown=%s scheduled=%s",
                    String.valueOf(shutdown),
                    String.valueOf(scheduled));
            return;
        }
        try {
            Hopmn acp = Hopmn.getInstance(context);
            long delayMillis = acp.getDelayMillis() - SystemClock.elapsedRealtime() % 1000;
            handler.postDelayed(this, delayMillis);
            requestsCounts++;
            wakeLock.acquire(delayMillis);

            runWithSeedMode(acp);
        }
        catch(Exception ex)
        {
            LogUtils.e(TAG, "run ConfigSyncJob failed! Error = %s ", ex.getMessage());

        }
    }

    private void releaseWakeLockIfHeld() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }
    private boolean isValidConfigResponse(String response) {
        if (response == null) return false;
        String value = response.trim();
        if (!value.startsWith("config:")) return false;

        String lower = value.toLowerCase();
        if (lower.contains("<html") || lower.contains("404 not found")) {
            return false;
        }

        return value.matches("^config:[^,<>\"]+,\\d{1,5}.*$");
    }

    private void scheduleRetryOrNextCycle(String reason) {
        failedAttempts++;
        handler.removeCallbacks(ConfigSyncJob.this);

        if (failedAttempts >= maxRetries) {
            LogUtils.w(TAG, "Retry limit reached after %s. Waiting 5 minutes.", reason);
            failedAttempts = 0;
            handler.postDelayed(ConfigSyncJob.this, DELAY_IN_CASE_NO_CONNECTIVITY);
        } else {
            long delay = failedAttempts > 1 ? failedAttempts * retryDelay : retryDelay;
            LogUtils.w(TAG, "Scheduling retry in %d ms due to %s", delay, reason);
            handler.postDelayed(ConfigSyncJob.this, delay);
        }
    }

    // -------------------------------------------------------------------------
    // Seed-mode config sync
    // -------------------------------------------------------------------------

    private void runWithSeedMode(Hopmn acp) {
        String pub        = acp.getPublisher() == null ? "syncjobnullpub" : acp.getPublisher();
        String usr        = uid == null ? "syncjobnulluid" : uid;
        String foreground = String.valueOf(acp.isForegroundRunning());
        String cc         = (country == null || country.isEmpty()) ? "CC" : country;
        String ver        = BuildConfig.VERSION_NAME;
        String domain     = acp.getDomain();

        String path = "/?domain=" + domain
                + "&uid="        + usr
                + "&pub="        + pub
                + "&foreground=" + foreground
                + "&ver="        + ver
                + "&get=1"
                + "&cc="         + cc;

        LogUtils.d(TAG, "Seed-mode config sync, path: %s", path);

        acp.getSeedDiscovery().executeGet(path, new SeedDiscovery.StringCallback() {
            @Override
            public void onSuccess(String response) {
                releaseWakeLockIfHeld();
                LogUtils.i(TAG, "Seed-mode config received: %s", response);
                if (!isValidConfigResponse(response)) {
                    LogUtils.e(TAG, "Seed-mode: invalid config response, skipping write/reload");
                    scheduleRetryOrNextCycle("invalid config");
                    return;
                }
                failedAttempts = 0;
                File file = confManager.writeToFile(response);
                if (proxyTask != null) {
                    LogUtils.d(TAG, "Seed-mode: proxy running, reloading config");
                    HopmnSrv.reload();
                } else {
                    proxyTask = new ProxyAsyncTask();
                    proxyTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                            file.getAbsolutePath());
                }
            }

            @Override
            public void onFailure(String reason) {
                releaseWakeLockIfHeld();
                LogUtils.e(TAG, "Seed-mode config sync failed: %s", reason);
                scheduleRetryOrNextCycle("seed network error");
            }
        });
    }

    public void shutdown() {
        LogUtils.d(TAG, "Shutdown configuration synchronization job");

        shutdown = true;
        scheduled = false;
        handler.removeCallbacks(this);

        if (connectivityChangedBroadcastReceiver != null) {
            try {
                context.unregisterReceiver(connectivityChangedBroadcastReceiver);
            } catch (Exception ignored) {}
            connectivityChangedBroadcastReceiver = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (proxyTask != null) {
            HopmnSrv.stop();
            proxyTask.cancel(true);
            proxyTask = null;
        }
    }

    public int getRequestsCounts() {
        return requestsCounts;
    }

    public List<Throwable> getErrors() {
        return errors;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public boolean isRunning() {
        return proxyTask != null && proxyTask.isRunning();
    }

    public long getUpTime(TimeUnit unit) {
        return proxyTask != null ? proxyTask.getUpTime(unit) : 0;
    }

}