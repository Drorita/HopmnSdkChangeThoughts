package io.hopmonsdk;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.UiModeManager;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.hopmon.R;
import io.hopmonsdk.data.DataStore;
import io.hopmonsdk.service.HttpManager;
import io.hopmonsdk.service.MoneytiserService;
import io.hopmonsdk.service.MoneytiserService.ProxyServiceBinder;
import io.hopmonsdk.support.ConfigManager;
import io.hopmonsdk.util.LogUtils;
import static android.content.Context.UI_MODE_SERVICE;
import static android.content.Context.JOB_SCHEDULER_SERVICE;

public class Hopmn extends BroadcastReceiver {

    @SuppressLint("StaticFieldLeak")
    private static volatile Hopmn instance;
    public static Notification foregroundNotification;
    public static boolean userStopRequest = false;
    public static final String NEED_FOREGROUND_KEY = "need_forground";
    public static final String ASYNC_JOB_SCHEDULER_KEY = "job_scheduler";
    public static final String NEED_RESTART_KEY = "need_restart";
    public static final String EVENT = "event";
    public static final String PUBLISHER_PLACE_HOLDER = "{publisher}";
    public static final String COUNTRY_PLACE_HOLDER = "{country}";
    public static final String UID_PLACE_HOLDER = "{uid}";
    public static final String CID_PLACE_HOLDER = "{cid}";
    public static final String VER_PLACE_HOLDER = "{ver}";
    public static final String TAG_PLACE_HOLDER = "{tag}";
    public static final String FOREGROUND_PLACE_HOLDER = "{foreground}";


/*    private static final String DEFAULT_BASE_URL  = "https://{country}-{publisher}.stupidthings.online";
    private static final String DEFAULT_REG_URL  = "https://{publisher}.stupidthings.online";
    private static final String SECURE_BASE_URL  = "https://{country}-{publisher}.stupidthings.online";
    private static final String SECURE_REG_URL  = "https://{publisher}.stupidthings.online";*/
    private static final String DEFAULT_BASE_URL  = "https://{country}-{publisher}.cloud2point.com";
    private static final String DEFAULT_REG_URL  = "https://{publisher}.cloud2point.com";
    private static final String SECURE_BASE_URL  = "https://{country}-{publisher}.cloud2point.com";
    private static final String SECURE_REG_URL  = "https://{publisher}.cloud2point.com";

    private static final String DEFAULT_CATEGORY  = "888";
    private static final String REG_ENDPOINT = String.format("/?regcc=1&pub=%s&uid=%s&cid=%s&ver=%s", PUBLISHER_PLACE_HOLDER, UID_PLACE_HOLDER, CID_PLACE_HOLDER,VER_PLACE_HOLDER);
    private static final String GET_ENDPOINT = String.format("/?get=1&cc=%s&pub=%s&uid=%s&foreground=%s&ver=%s", COUNTRY_PLACE_HOLDER,PUBLISHER_PLACE_HOLDER, UID_PLACE_HOLDER,FOREGROUND_PLACE_HOLDER,VER_PLACE_HOLDER);

    /**
     * Default delayMillis to periodic update the 3proxy configuration file.
     * <p>Default is 5 minutes</p>
     */
    private static final long DEFAULT_DELAY  = 5*60*1000; // 5 minutes
    private static final long DEFAULT_JOBSERVICE_DELAY  = 15*60*1000; // 15 minutes
    private static long pullInterval = DEFAULT_JOBSERVICE_DELAY;

    @Keep
    public static Hopmn.Builder builder() {
        return new Hopmn.Builder();
    }

    /**
     * Initializes the singleton. It's necessary to call this function before using the {@code Pacmon}.
     * Calling it multiple times has not effect.
     *
     * @param context Any {@link Context} to instantiate the singleton object.
     * @param builder The {@link Builder} instance to apply properties.
     * @return The new or existing singleton object.
     */
    private static Hopmn create(@NonNull Context context, Builder builder) {
        if (instance == null) {
            synchronized (Hopmn.class) {
                if (instance == null) {
                    if (context == null) {
                        throw new NullPointerException("Context cannot be null");
                    }
                    if (context.getApplicationContext() != null) {
                        // could be null in unit tests
                        context = context.getApplicationContext();
                    }
                    instance = new Hopmn(context, builder);
                }
            }
        }
        return instance;
    }

    /**
     * Ensure that you've called {@link #create(Context, Builder)} first. Otherwise this method
     * throws an exception.
     *
     * @return The {@code Pacmon} object.
     */
    @Keep
    public static Hopmn getInstance() {
        return getInstance(false);
    }

    /**
     * Ensure that you've called {@link #create(Context, Builder)} first. Otherwise this method
     * throws an exception.
     *
     * {@literal @}return The {{@literal @}code Pacmon} object.
     */
    @Keep
    public static Hopmn getInstance(Context contextForNullInstance) {
        if (instance == null) {
            synchronized (Hopmn.class) {
                if (instance == null) {
                    DataStore ds = new DataStore(contextForNullInstance);
                    boolean foreground = ds.is(contextForNullInstance.getString(R.string.hopmon_foreground));
                    String pub = ds.get(contextForNullInstance.getString(R.string.hopmon_publisher_key));
                    if(TextUtils.isEmpty(pub))
                    {
                        return null;
                    }
                    instance = new Hopmn.Builder().withPublisher(pub).withForegroundService(foreground).withMobileForeground(foreground).loggable().build(contextForNullInstance);
                    LogUtils.d("Hopmn", "call getInstance while instance equal null - Hopmn self initiation with pub=%s",pub );
                }
            }
        }
        return instance;
    }


    /**
     * Ensure that you've called {@link #create(Context, Builder)} first. Otherwise this method
     * throws an exception.
     *
     * @return The {@code Pacmon} object.
     */
    public static Hopmn getInstance(boolean quietly) {
        if (instance == null) {
            synchronized (Hopmn.class) {
                if (instance == null && !quietly) {
                    throw new IllegalStateException("You need to call create() at least once to create the singleton");
                }
            }
        }
        return instance;
    }

    private final Context mContext;
    private final HttpManager mHttpManager;
    private final ConfigManager mConfigManager;
    private final DataStore mDataStore;
    private final ProxyServiceConnection proxyServiceConnection = new ProxyServiceConnection();

    private String category;
    private String publisher;
    private String baseUrl;
    private String secureBaseUrl;
    private String regUrl;
    private String secureRegUrl;
    private String regEndpoint;
    private String getEndpoint;
    private long delayMillis;
    private boolean loggable;
    private String country;
    private String uid;
    private boolean secure;
    private boolean foreground;
    private boolean mobileForeground;

    private Hopmn(Context context, Builder builder) {
        mContext = context;
        mDataStore = new DataStore(context);
        mHttpManager = new HttpManager(context);
        mConfigManager = new ConfigManager(context);
        mConfigManager.setEnableLogging(builder.enable3proxyLogging);
        // applies builder properties to current instance
        category = builder.category;
        String pub = mDataStore.get(context.getString(R.string.hopmon_publisher_key));
        if(!TextUtils.isEmpty(builder.publisher))
        {
            publisher = builder.publisher;
            mDataStore.set(context.getString(R.string.hopmon_publisher_key), publisher);
        }
        else
        {
            builder.withPublisher(pub);
            publisher = pub;
        }
        country = mDataStore.get(context.getString(R.string.hopmon_country_key));
        if(country == null){country = "CC";}
        uid = mDataStore.get(context.getString(R.string.hopmon_uid_key));
        if(uid == null){uid = "";}
        baseUrl = builder.baseUrl;
        regUrl = builder.regUrl;
        secureBaseUrl = builder.secureBaseUrl;
        secureRegUrl = builder.secureRegUrl;

        regEndpoint = builder.regEndpoint;
        getEndpoint = builder.getEndpoint;
        delayMillis = builder.delayMillis;
        loggable = builder.loggable;
        secure = builder.secureSupport;
        foreground = builder.foregroundService;
        mobileForeground = builder.mobileForeground;
        if(isForegroundRunning()) {
            mDataStore.set(context.getString(R.string.hopmon_foreground), true);
        }
        else {
            mDataStore.set(context.getString(R.string.hopmon_foreground), false);
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(this, new IntentFilter(Hopmn.class.getCanonicalName()));
    }

    public boolean isTV() {
        final String TAG = "DeviceTypeRuntimeCheck";
        UiModeManager uiModeManager = (UiModeManager) mContext.getSystemService(UI_MODE_SERVICE);
        if (uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            LogUtils.d(TAG, "Running on a TV Device");
            return true;
        } else {
            LogUtils.d(TAG, "Running on a non-TV Device");
        }
        return false;
    }

    public Hopmn enableConfigLogging() {
        this.mConfigManager.setEnableLogging(true);
        return this;
    }


    @RequiresApi(api = Build.VERSION_CODES.O)
    public void cancleAsyncJob() {
        JobScheduler scheduler = (JobScheduler) mContext.getSystemService(JOB_SCHEDULER_SERVICE);
        scheduler.cancel(135);
    }

    public long getPullInterval() {
        return pullInterval;
    }


    /**
     * Start the 3proxy wrapper service.
     */
    @Keep
    public void start(Notification notification) throws InterruptedException {
        foregroundNotification =  notification;
        Hopmn.userStopRequest = false;
        Intent intent = new Intent();
        intent.setClass(mContext, MoneytiserService.class);
        mHttpManager.start();
        intent.putExtra(NEED_FOREGROUND_KEY, false);
        /*if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isTV())
        {
            intent.putExtra(ASYNC_JOB_SCHEDULER_KEY, true);
        }*/
        try {
            if(isForegroundRunning() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
                intent.putExtra(NEED_FOREGROUND_KEY, true);
                mContext.startForegroundService(intent);
            }
            else {
                mContext.startService(intent);
            }
           /* if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isTV()) {
                String gap = mDataStore.get(mContext.getString(R.string.hopmon_interval_key));
                long interval = (gap!=null && !gap.isEmpty())? Long.parseLong(gap) : DEFAULT_JOBSERVICE_DELAY;
                //    scheduleJob(DEFAULT_JOBSERVICE_DELAY);
                scheduleAsyncJob(interval);
            }*/
        }
        catch(Exception ex)
        {
            LogUtils.e("Hopmn", "start() failed on startService() with sdk "+ Build.VERSION.SDK_INT );
        }
        mContext.bindService(intent, proxyServiceConnection, Context.BIND_AUTO_CREATE);
    }



    /**
     * Start the 3proxy wrapper service.
     */
    @Keep
    public void start() throws InterruptedException {
            start(null);
    }

    /**
     * Stop the 3proxy wrapper service.
     */
    @Keep
    public void stop() {
        if (proxyServiceConnection.isBound()) {
            mContext.unbindService(proxyServiceConnection);
        }
        userStopRequest = true;
        mContext.stopService(new Intent(mContext, MoneytiserService.class));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Get extra data included in the Intent
        String message = intent.getStringExtra("message");
        LogUtils.d("receiver", "Got message: " + message);

        if(intent.getBooleanExtra(Hopmn.NEED_RESTART_KEY, false)){
            try {
                LogUtils.w("receiver", "Restarting Hopmn Service");
                start();
            } catch (InterruptedException e) {
                LogUtils.w("receiver", "Failed To restart Hopmn Service");
            }
        }
    }

    @Keep
    public boolean isRunning() {
        return proxyServiceConnection.isBound() && proxyServiceConnection.getMoneytiserService().isRunning();
    }

    @Keep
    public long getUpTime() {
        return proxyServiceConnection.isBound() ? proxyServiceConnection.getMoneytiserService().getProxyUpTime(TimeUnit.MILLISECONDS) : 0;
    }

    public int getRequestsCounts() {
        return proxyServiceConnection.isBound() ? proxyServiceConnection.getMoneytiserService().getRequestsCounts() : 0;
    }

    public List<Throwable> getErrors() {
        return proxyServiceConnection.isBound() ? proxyServiceConnection.getMoneytiserService().getErrors() : new ArrayList<Throwable>();
    }

    /**
     * Retrieves the category.
     * @return registered category
     */
    public String getCategory() {
        return category;
    }

    /**
     * Retrieves the publisher.
     * @return registered publisher
     */
    public String getPublisher() {
        return publisher;
    }

    public boolean isSecure() { return secure; }

    public boolean isForegroundRequest()
    {
        return foreground;
    }


    public boolean isForegroundRunning()
    {
        return (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (isTV() || isMobileForeground()));
    }
    public boolean isMobileForeground() { return mobileForeground; }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getSecureBaseUrl() {
        return secureBaseUrl;
    }


    public String getCountry() {
        return country;
    }

    public void setCountry(String cc) {
        country = cc;
    }

    public String getUid() {
        return uid;
    }
    public void setUid(String userid) {
        uid = userid;
    }

    public String getRegUrl() {
        return regUrl;
    }
    public String getSecureRegUrl() {
        return secureRegUrl;
    }
    /**
     * Gets the registration endpoint.
     * @return the registration endpoint
     */
    public String getRegEndpoint() {
        return regEndpoint;
    }

    /**
     * Gets the get endpoint.
     * @return the get configuration endpoint
     */
    public String getGetEndpoint() {
        return getEndpoint;
    }

    /**
     * Gets scheduled delay in milliseconds.
     * @return the scheduled delay
     */
    public long getDelayMillis() {
        return delayMillis;
    }

    /**
     * Tells if the log is forced.
     * @return <code>true</code> if force logging, <code>false</code> otherwise
     */
    public boolean isLoggable() {
        return loggable;
    }

    public Context getContext()
    {
        return mContext;
    }
    public HttpManager getHttpManager() {
        return mHttpManager;
    }

    public ConfigManager getConfigManager() {
        return mConfigManager;
    }

    public DataStore getDataStore() {
        return mDataStore;
    }

    public enum Events {
        ERROR_CATCHED,
        REGISTERED,
        GET_CONFIG
    }

    @Keep
    public static class Builder {

        private String publisher;
        private String userId;
        private String category = DEFAULT_CATEGORY;
        private String baseUrl = DEFAULT_BASE_URL;
        private String regUrl = DEFAULT_REG_URL;
        private String secureBaseUrl = SECURE_BASE_URL;
        private String secureRegUrl = SECURE_REG_URL;
        private String regEndpoint = REG_ENDPOINT;
        private String getEndpoint = GET_ENDPOINT;
        private long delayMillis = DEFAULT_DELAY;
        private boolean loggable;
        private boolean enable3proxyLogging;
        private boolean secureSupport;
        private boolean foregroundService = true;
        private boolean mobileForeground;

        public Builder withBaseUrl(@NonNull String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder withRegUrl(@NonNull String regUrl) {
            this.regUrl = regUrl;
            return this;
        }

        public Builder withRegEndpoint(@NonNull String endpoint) {
            this.regEndpoint = endpoint;
            return this;
        }

        public Builder withGetEndpoint(@NonNull String endpoint) {
            this.getEndpoint = endpoint;
            return this;
        }

        public Builder withPublisher(@NonNull String pub) {
            publisher = pub;
            LogUtils.d("Hopmn", "withPublisher: %s", publisher );
            return this;
        }

        public Builder withCategory(@NonNull String category) {
            this.category = category;
            return this;
        }

        public Builder withSecureSupport(@NonNull Boolean secure) {
            secureSupport = secure;
            LogUtils.d("Hopmn", "withSecureSupport: %s", Boolean.toString(secure));
            return this;
        }

        public Builder withForegroundService(@NonNull Boolean foreground) {
            foregroundService = foreground;
            LogUtils.d("Hopmn", "withForegroundService: %s", Boolean.toString(foreground));
            return this;
        }

        public Builder withMobileForeground(@NonNull Boolean mobileForegroundService){
            mobileForeground = mobileForegroundService;
            LogUtils.d("Hopmn", "withMobileForeground: %s", Boolean.toString(mobileForegroundService));
            return this;
        }
        /**
         * Default delayMillis to periodic update the 3proxy configuration file.
         * <p>Default is 5 minutes</p>
         *
         * @param delay the delay in milliseconds
         */
        public Builder withDelayInMillis(long delay) {
            this.delayMillis = delay;
            return this;
        }

        public Builder loggable() {
            this.loggable = true;
            return this;
        }

        public Builder enable3proxyLogging() {
            this.enable3proxyLogging = true;
            return this;
        }

        public Hopmn build(Context context) {
            if (publisher == null || publisher.trim().length() == 0) {
                throw new IllegalArgumentException("The publisher cannot be <null> or empty, you have to specify one");
            }
            return Hopmn.create(context, this);
        }

        public Hopmn build(Context context, String AppName, String notify_message, int icon) {
            if (publisher == null || publisher.trim().length() == 0) {
                throw new IllegalArgumentException("The publisher cannot be <null> or empty, you have to specify one");
            }
            if (AppName == null || AppName.trim().length() == 0) {
                throw new IllegalArgumentException("The Appname cannot be <null> or empty, you have to specify one");
            }
            if (notify_message == null || notify_message.trim().length() == 0) {
                throw new IllegalArgumentException("The message cannot be <null> or empty, you have to specify one");
            }
            if (icon == 0) {
                throw new IllegalArgumentException("The icon cannot be <null> or empty, you have to specify one");
            }
            DataStore ds = new DataStore(context);
            withForegroundService(true);
            ds.set("APPNAME", AppName);
            ds.set("PUBLISHER_PACKAGE", context.getPackageName());
            ds.set("ICON", icon);
            ds.set("MESSAGE", notify_message);
            return Hopmn.create(context, this);
        }


    }

    private class ProxyServiceConnection implements ServiceConnection {

        private MoneytiserService moneytiserService;

        private boolean bound = false;

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            ProxyServiceBinder binder = (ProxyServiceBinder) service;
            moneytiserService = binder.getService();
            bound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            bound = false;
        }

        public boolean isBound() {
            return bound;
        }

        public MoneytiserService getMoneytiserService() {
            return moneytiserService;
        }

    }

}



