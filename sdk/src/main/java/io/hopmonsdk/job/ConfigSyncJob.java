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
import io.hopmonsdk.HopmnProxy;
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

    private Handler handler = new Handler();

    private PowerManager.WakeLock wakeLock;

    private long retryDelay = 2000;

    private int maxRetries = 15;

    private int requestsCounts = 0;

    private int failedAttempts = 0;

    private String uid;
    
    private String country;

    private static final long DELAY_IN_CASE_NO_CONNECTIVITY  = 5*60*1000; // 5 minutes

    private NetworkStateReceiver connectivityChangedBroadcastReceiver;

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
        if (proxyTask == null || !proxyTask.isRunning()) {
            uid = userId;
            country = cc;
            handler.removeCallbacks(this);
            handler.post(this);
            LogUtils.d(TAG, "Scheduled configuration synchronization job");
        } else {
            LogUtils.w(TAG, "The hopmnproxy task already running, cannot reschedule a new one");
        }
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

        try {
            Hopmn acp = Hopmn.getInstance(context);
            long delayMillis = acp.getDelayMillis() - SystemClock.elapsedRealtime() % 1000;
            handler.postDelayed(this, delayMillis);
            requestsCounts++;
            wakeLock.acquire(delayMillis);
            // request a string response from the provided URL.
            String pub = acp.getPublisher() == null ? "syncjobnullpub" : acp.getPublisher();
            String usr = uid == null ? "syncjobnulluid" : uid;
            String foreground = String.valueOf(acp.isForegroundRunning());
            String baseUrl = acp.isSecure() ? acp.getSecureBaseUrl() : acp.getBaseUrl();
            String getEndpoint = acp.getGetEndpoint();
            if (!baseUrl.endsWith("/") && !getEndpoint.startsWith("/")) {
                baseUrl += "/";
            }
            country = (country == null || country.isEmpty()) ? "CC" : country;
            String ver = BuildConfig.VERSION_NAME;
            // Request a string response from the provided URL.
            String url = baseUrl.replace(Hopmn.COUNTRY_PLACE_HOLDER, country).replace(Hopmn.PUBLISHER_PLACE_HOLDER, pub) + getEndpoint.replace(Hopmn.COUNTRY_PLACE_HOLDER, country).replace(Hopmn.PUBLISHER_PLACE_HOLDER, pub).replace(Hopmn.UID_PLACE_HOLDER, usr).replace(Hopmn.FOREGROUND_PLACE_HOLDER, foreground).replace(Hopmn.VER_PLACE_HOLDER, ver);
            LogUtils.d(TAG, "Updating hopmnproxy configuration calling url: %s", url);
            Intent intent = new Intent(Hopmn.class.getCanonicalName());
            intent.putExtra(Hopmn.EVENT, Hopmn.Events.GET_CONFIG);
            intent.putExtra("requestedUrl", url);
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
            StringRequest request = new StringRequest(Request.Method.GET, url,
                    new Response.Listener<String>() {
                        @Override
                        public void onResponse(String response) {
                            failedAttempts = 0;
                            LogUtils.d(TAG, "5m reload update configuration...");
                            LogUtils.i(TAG, "New configuration directive: %s", response);
                            File file = confManager.writeToFile(response);
                            if (proxyTask != null) {
                                LogUtils.d(TAG, "Proxy task is running, try to reload configuration");
                                HopmnProxy.reload();
                            } else {
                                proxyTask = new ProxyAsyncTask();
                                proxyTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, file.getAbsolutePath());
                            }
                        }
                    },
                    new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            NetworkResponse networkResponse = error.networkResponse;
                            LogUtils.e(TAG, "An error occurred while calling configuration service: %s, %s", error.fillInStackTrace(), error.getMessage(), networkResponse != null ? networkResponse.statusCode : "<none>");
                            failedAttempts++;
                            if (errors.size() >= maxRetries) {
                                errors.remove(0);
                            }
                            errors.add(error);
                            handler.removeCallbacks(ConfigSyncJob.this);
                            if (failedAttempts >= maxRetries) {
                                LogUtils.d(TAG, "Max retrieves for failed attempts are reached");
                                handler.postDelayed(ConfigSyncJob.this, DELAY_IN_CASE_NO_CONNECTIVITY);
                            } else if (failedAttempts > 1) {
                                handler.postDelayed(ConfigSyncJob.this, failedAttempts * retryDelay);
                            } else {
                                handler.post(ConfigSyncJob.this);
                            }
                        }
                    }
            );
            httpManager.addToRequestQueue(request);
        }
        catch(Exception ex)
        {
            LogUtils.e(TAG, "run ConfigSyncJob failed! Error = %s ", ex.getMessage());

        }
    }

    public void shutdown() {
        LogUtils.d(TAG, "Shutdown configuration synchronization job");
        if(connectivityChangedBroadcastReceiver != null){
        context.unregisterReceiver(connectivityChangedBroadcastReceiver);
        }
        if(wakeLock.isHeld()){
            wakeLock.release();
            }
        handler.removeCallbacks(this);
        if (proxyTask != null) {
            HopmnProxy.stop();
            proxyTask.cancel(true);
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