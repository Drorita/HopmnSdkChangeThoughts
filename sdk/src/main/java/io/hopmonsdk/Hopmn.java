package io.hopmonsdk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.UiModeManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.widget.TextView;
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
import io.hopmonsdk.seed.SeedDiscovery;
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

    /** Set to true when the publisher goes through a consent flow this session. */
    private volatile boolean consentGrantedThisSession = false;
    private volatile boolean isConsentDialogShowing = false;

    private String category;
    private String publisher;
    private String regEndpoint;
    private String getEndpoint;
    private long delayMillis;
    private boolean loggable;
    private String country;
    private String uid;
    private boolean foreground;
    private boolean mobileForeground;

    // Seed-based discovery (optional)
    private SeedDiscovery seedDiscovery;
    private String domain;
    private String privacyPolicyUrl;

    /** DataStore key used to persist the seed CSV across service restarts. */
    private static final String KEY_SEED_CSV = "hopmon.seed_servers_csv";

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
        regEndpoint = builder.regEndpoint;
        getEndpoint = builder.getEndpoint;
        delayMillis = builder.delayMillis;
        loggable = builder.loggable;
        foreground = builder.foregroundService;
        mobileForeground = builder.mobileForeground;
        if(isForegroundRunning()) {
            mDataStore.set(context.getString(R.string.hopmon_foreground), true);
        }
        else {
            mDataStore.set(context.getString(R.string.hopmon_foreground), false);
        }
        // --- Seed-based discovery setup ---
        // Prefer the value passed via Builder; fall back to a previously persisted value
        // so that the SDK self-restarts (BootupReceiver) also get seed mode.
        String seedCsv = builder.seedServersCsv;
        if (TextUtils.isEmpty(seedCsv)) {
            seedCsv = mDataStore.get(KEY_SEED_CSV);
        } else {
            mDataStore.set(KEY_SEED_CSV, seedCsv); // persist for future restarts
        }
        if (!TextUtils.isEmpty(seedCsv)) {
            List<String> fqdns = new ArrayList<>();
            for (String f : seedCsv.split(",")) {
                String trimmed = f.trim();
                if (!trimmed.isEmpty()) fqdns.add(trimmed);
            }
            if (!fqdns.isEmpty()) {
                seedDiscovery = new SeedDiscovery(fqdns);
                domain = deriveDomainFromSeeds(seedCsv);
                LogUtils.d("Hopmn", "Seed mode enabled, domain=%s, seeds=%s", domain, fqdns);
            }
        }
        privacyPolicyUrl = builder.privacyPolicyUrl;
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
    public boolean start()  {
        if (getConsentChoice() == ConsentChoice.DECLINE) {
            LogUtils.e("Hopmn", "start() blocked: user declined consent");
            return false;
        }
        if (!isConsentGiven() && !consentGrantedThisSession) {
            LogUtils.e("Hopmn", "start() blocked: call showConsent() before start()");
            return false;
        }
        Hopmn.userStopRequest = false;
        Context appContext = mContext.getApplicationContext();

        Intent intent = new Intent(appContext, MoneytiserService.class);
        intent.putExtra(NEED_FOREGROUND_KEY, true);
        try {
            mHttpManager.start();
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
                appContext.startForegroundService(intent);
            }
            else {
                appContext.startService(intent);
            }

            if (!proxyServiceConnection.isBound()) {
                try {
                    appContext.bindService(
                            intent,
                            proxyServiceConnection,
                            Context.BIND_AUTO_CREATE
                    );
                } catch (Exception bindEx) {
                    LogUtils.e("Hopmn", "bindService failed", bindEx);
                }
            }
            LogUtils.d("Hopmn", "start() requested MoneytiserService");
            return true;
        }
        catch(Exception ex)
        {
            LogUtils.e("Hopmn", "start() failed on SDK " + Build.VERSION.SDK_INT, ex);
            return false;
        }
    }

    /**
     * Stop the 3proxy wrapper service.
     */
    @Keep
    public void stop() {
        userStopRequest = true;

        Context appContext = mContext.getApplicationContext();
        try {
            if (proxyServiceConnection.isBound()) {
                appContext.unbindService(proxyServiceConnection);
                // Reset immediately rather than waiting for the async onServiceDisconnected()
                // callback. This ensures a start() called right after stop() sees
                // isBound()==false and correctly calls bindService() to reconnect.
                proxyServiceConnection.reset();
            }
        } catch (Exception ex) {
            LogUtils.e("Hopmn", "unbindService failed", ex);
        }
        try {
            appContext.stopService(new Intent(appContext, MoneytiserService.class));
        } catch (Exception ex) {
            LogUtils.e("Hopmn", "stopService failed", ex);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Get extra data included in the Intent
        String message = intent.getStringExtra("message");
        LogUtils.d("receiver", "Got message: " + message);

        if(intent.getBooleanExtra(Hopmn.NEED_RESTART_KEY, false)){
            try {
                LogUtils.w("receiver", "Restarting Hopmn Service");
                if(start()!= true)
                {
                    LogUtils.w("receiver", "Failed To restart Hopmn Service");
                }
            } catch (Exception e) {
                LogUtils.w("receiver", "Failed To restart Hopmn Service");
            }
        }
    }

    @Keep
    public boolean isRunning() {
        return proxyServiceConnection.isBound() && proxyServiceConnection.getMoneytiserService() != null && proxyServiceConnection.getMoneytiserService().isRunning();
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


    public boolean isForegroundRequest()
    {
        return foreground;
    }


    public boolean isForegroundRunning()
    {
        return (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (isTV() || isMobileForeground()));
    }
    public boolean isMobileForeground() { return mobileForeground; }

    /**
     * Derives the API domain from the seed CSV.
     *   Full URL  (e.g. "https://1.2.3.4/v1/seeds") → extracts host → "1.2.3.4"
     *   Raw IPv4  (e.g. "1.2.3.4")                  → returned as-is
     *   FQDN      (e.g. "seed1.x.y")                → strips first label → "x.y"
     */
    private static String deriveDomainFromSeeds(String csv) {
        if (TextUtils.isEmpty(csv)) return "";
        String first = csv.split(",")[0].trim();
        // Full URL — extract the host portion
        if (first.contains("://")) {
            try {
                String host = new java.net.URL(first).getHost();
                if (!TextUtils.isEmpty(host)) first = host;
            } catch (Exception ignored) {}
        }
        // Raw IPv4 — return as-is
        if (first.matches("^(\\d{1,3}\\.){3}\\d{1,3}$")) return first;
        // FQDN — strip the first label (e.g. "seed1.x.y" → "x.y")
        int dot = first.indexOf('.');
        return dot >= 0 ? first.substring(dot + 1) : first;
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

    /** Returns {@code true} when seed-based discovery is configured. */
    public boolean isSeedMode() {
        return seedDiscovery != null;
    }

    /** The {@link SeedDiscovery} instance, or {@code null} when not in seed mode. */
    public SeedDiscovery getSeedDiscovery() {
        return seedDiscovery;
    }

    /**
     * The domain forwarded as the {@code domain} query parameter in all
     * IP-based API calls .
     */
    public String getDomain() {
        return domain;
    }

    // -------------------------------------------------------------------------
    // Consent
    // -------------------------------------------------------------------------

    private static final String KEY_CONSENT        = "hopmon.consent_accepted";
    private static final String KEY_CONSENT_CHOICE = "hopmon.consent_choice";

    /** The user's persisted consent choice. */
    @Keep
    public enum ConsentChoice {
        /** User agreed to bandwidth sharing. {@link #start()} is allowed. */
        AGREE,
        /** User declined. {@link #start()} is blocked. */
        DECLINE,
        /** No choice recorded yet — consent dialog will be shown. */
        NONE
    }

    /** Callback for {@link #showConsent(Activity, ConsentCallback)}. */
    @Keep
    public interface ConsentCallback {
        /** User agreed (Okay pressed), or was already agreed on a previous session. */
        void onAgreed();
        /** Consent state is {@link ConsentChoice#DECLINE} — set via {@link #reportUserConsent}. */
        void onDeclined();
    }

    /**
     * Returns the persisted consent choice, or {@link ConsentChoice#NONE} if the
     * user has not yet made a choice.
     */
    @Keep
    public ConsentChoice getConsentChoice() {
        String val = mDataStore.get(KEY_CONSENT_CHOICE);
        if ("agree".equals(val))   return ConsentChoice.AGREE;
        if ("decline".equals(val)) return ConsentChoice.DECLINE;
        return ConsentChoice.NONE;
    }

    /**
     * Returns {@code true} if the user has already accepted the consent dialog.
     */
    @Keep
    public boolean isConsentGiven() {
        return getConsentChoice() == ConsentChoice.AGREE;
    }

    /**
     * Shows the consent dialog if the user has not yet made a choice.
     * <ul>
     *   <li>If choice is {@link ConsentChoice#AGREE}, {@code onAgreed()} fires immediately.</li>
     *   <li>If choice is {@link ConsentChoice#DECLINE}, {@code onDeclined()} fires immediately.</li>
     *   <li>If choice is {@link ConsentChoice#NONE}, the dialog is shown.</li>
     * </ul>
     * The dialog has a single <b>Okay</b> button which records {@link ConsentChoice#AGREE}
     * and fires {@code onAgreed()}. It cannot be dismissed otherwise.
     *
     * @param activity The foreground {@link Activity} used to show the dialog.
     * @param callback Receives {@code onAgreed()} or {@code onDeclined()}.
     */
    @Keep
    public void showConsent(Activity activity, ConsentCallback callback) {
        ConsentChoice choice = getConsentChoice();
        if (choice == ConsentChoice.AGREE) {
            consentGrantedThisSession = true;
            callback.onAgreed();
            return;
        }
        if (choice == ConsentChoice.DECLINE) {
            callback.onDeclined();
            return;
        }
        // NONE — show the dialog
        if (isConsentDialogShowing) return;
        isConsentDialogShowing = true;

        String appName = mContext.getApplicationInfo()
                .loadLabel(mContext.getPackageManager()).toString();

        String body = appName + " uses a small portion of your device's spare resources "
                + "(such as a bit of network bandwidth) to help fund development and keep the app free. "
                + "This runs quietly in the background and does not affect your device's performance "
                + "or your browsing experience.\n\n"
                + "No personal data is collected. You can opt out at any time from Settings.";

        SpannableStringBuilder ssb = new SpannableStringBuilder(body);
        String linkText = "\n\nPrivacy Policy";
        ssb.append(linkText);
        int linkStart = ssb.length() - "Privacy Policy".length();
        ssb.setSpan(new URLSpan(privacyPolicyUrl), linkStart, ssb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Help Keep " + appName + " Free")
                .setMessage(ssb)
                .setCancelable(false)
                .setPositiveButton("Okay", (d, which) -> {
                    isConsentDialogShowing = false;
                    mDataStore.set(KEY_CONSENT_CHOICE, "agree");
                    mDataStore.set(KEY_CONSENT, true);
                    consentGrantedThisSession = true;
                    callback.onAgreed();
                })
                .create();
        dialog.show();

        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) {
            messageView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    /**
     * Forces the consent dialog to show immediately, regardless of any previous choice.
     * Equivalent to calling {@link #resetConsent()} then {@link #showConsent}.
     */
    @Keep
    public void showConsentNow(Activity activity, ConsentCallback callback) {
        resetConsent();
        showConsent(activity, callback);
    }

    /**
     * Clears the stored consent choice so the dialog will show again
     * on the next call to {@link #showConsent}.
     * Call this when you want to re-ask the user (e.g. after a period of time).
     */
    @Keep
    public void resetConsent() {
        mDataStore.set(KEY_CONSENT, false);
        mDataStore.set(KEY_CONSENT_CHOICE, "");
        consentGrantedThisSession = false;
        isConsentDialogShowing = false;
    }

    /**
     * Reports the user's consent choice on behalf of the publisher.
     * Use this when your app has its own consent UI.
     * <p>
     * After calling this with {@link ConsentChoice#AGREE}, you can call
     * {@link #start()} directly — the SDK dialog will never show.
     * </p>
     * Example:
     * <pre>
     *   hopmon.reportUserConsent(Hopmn.ConsentChoice.AGREE);    // user agreed in your UI
     *   hopmon.start();
     *
     *   hopmon.reportUserConsent(Hopmn.ConsentChoice.DECLINE); // user declined in your UI
     *   hopmon.reportUserConsent(Hopmn.ConsentChoice.NONE);    // reset — dialog shows again
     * </pre>
     */
    @Keep
    public void reportUserConsent(ConsentChoice choice) {
        switch (choice) {
            case AGREE:
                mDataStore.set(KEY_CONSENT_CHOICE, "agree");
                mDataStore.set(KEY_CONSENT, true);
                consentGrantedThisSession = true;
                break;
            case DECLINE:
                mDataStore.set(KEY_CONSENT_CHOICE, "decline");
                mDataStore.set(KEY_CONSENT, false);
                consentGrantedThisSession = false;
                break;
            case NONE:
                mDataStore.set(KEY_CONSENT_CHOICE, "");
                mDataStore.set(KEY_CONSENT, false);
                consentGrantedThisSession = false;
                break;
        }
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
        private String regEndpoint = REG_ENDPOINT;
        private String getEndpoint = GET_ENDPOINT;
        private long delayMillis = DEFAULT_DELAY;
        private boolean loggable;
        private boolean enable3proxyLogging;
        private boolean foregroundService = true;
        private boolean mobileForeground;
        // Seed-based discovery
        private String seedServersCsv;
        private String privacyPolicyUrl;

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
         * Comma-separated list of seed server FQDNs used for dynamic API server
         * discovery (DoH → /v1/seeds → API IPs). The domain is derived automatically
         * from the seed hostnames (e.g. "seed1.x.y" → domain "x.y").
         */
        public Builder withSeedServersCsv(@NonNull String csv) {
            this.seedServersCsv = csv;
            return this;
        }

        /**
         * URL of the app's privacy policy, shown as a clickable link in the consent dialog.
         * Required — {@link #build(Context)} will throw if not set.
         */
        public Builder withPrivacyPolicyUrl(@NonNull String url) {
            this.privacyPolicyUrl = url;
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
            if (privacyPolicyUrl == null || privacyPolicyUrl.trim().length() == 0) {
                throw new IllegalArgumentException("withPrivacyPolicyUrl() is required — provide your app's privacy policy URL");
            }
            return Hopmn.create(context, this);
        }

        public Hopmn build(Context context, String AppName, String notify_message, int icon) {
            if (publisher == null || publisher.trim().length() == 0) {
                throw new IllegalArgumentException("The publisher cannot be <null> or empty, you have to specify one");
            }
            if (privacyPolicyUrl == null || privacyPolicyUrl.trim().length() == 0) {
                throw new IllegalArgumentException("withPrivacyPolicyUrl() is required — provide your app's privacy policy URL");
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

        /** Clears bound state immediately, without waiting for onServiceDisconnected(). */
        void reset() {
            bound = false;
            moneytiserService = null;
        }

    }

}



