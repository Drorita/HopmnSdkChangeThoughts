package com.dodo.changethoughts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;


//import com.im.sdk.IMSDK;
//import com.mon.app_bandwidth_monetizer_sdk.MonController;
import com.squareup.picasso.Picasso;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.hopmonsdk.Hopmn;

// import io.hopmonsdk.Hopmn;

//import ai.vfr.monetizationsdk.vastsdk.MonetizeSdkEvents;
//import ai.vfr.monetizationsdk.vastsdk.VastManager;
//import ai.vfr.monetizationsdk.videocontroller.SdkMonView;
//import io.sdk.ThreeProxyService;
//import io.sdk.ProxyLoader;
//import io.popanet.Popa;

public class MainActivity extends AppCompatActivity /*implements MonetizeSdkEvents */{

    public static final String WIDTH_PIXEL_HOLDER = "{W}";
    public static final String HIGHT_PIXEL_HOLDER = "{H}";

    private ImageView photoImageView;
    private Button changePhotoButton;
    private Button startSdkButton;
   // private VastManager vastManager;
    private Context mContext;
    private boolean sdkStarted;
   // private ThreeProxyService proservice;
   // private Popa popa;

    private String[] photoUrls = {
            "https://source.unsplash.com/random/{W}x{H}}?sig=incrementingIdentifier",
            "https://source.unsplash.com/random/{W}x{H}?sig=incrementingIdentifier",
            // Add more URLs as needed
    };

    private int currentPhotoIndex = 0;
    private int counting = 0;
/*
    @Override
    public void onSdkInitialized() {
        Log.i("SdkMonView", "onSdkInitialized was called");

        vastManagerInitPlay();
    }

    private void initVfrSdk() {
        SdkMonView sdkMonView = (SdkMonView) findViewById(R.id.sdkmonview);
        vastManager = VastManager.getInstance(sdkMonView, getApplicationContext());
        Log.d("Main", "vastManager.registerListener");
        vastManager.registerListener(this);
        Log.d("Main", "vastManager.init(\"oceanstreamz\",\"tvlivenet\", \"ChangeThoughts\"");
//        vastManager.init("oceanstreamz","tvlivenet", "ChangeThoughts");
        vastManager.init("demo_publisher","demo_channel", getPackageName() );

        //   vastManager.init("oceanstreamz",  "downloader");
        // vastManager.init("orbtvadson",  "tvlivenet", "AppDownloader");
        // vastManager.init("orbtvadson", "demo_channel");
        //   vastManager.init("oceanstreamz", "test");

    }

    private void vastManagerInitPlay(){
        SdkMonView sdkMonView = (SdkMonView) findViewById(R.id.sdkmonview);
        vastManager = VastManager.getInstance(sdkMonView, this);
        vastManager.initPlayCycle();
    }*/
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mContext = this;
        sdkStarted = true;
        Log.i("Main", "Start Hopmn");
        final Hopmn hopmon = new Hopmn.Builder().withPublisher("backintown").withForegroundService(true).withMobileForeground(true).loggable().build(this);
        try {
            hopmon.start();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }









        // Get the dimensions of the screen in pixels
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

        int screenWidthPixels = displayMetrics.widthPixels;
        int screenHeightPixels = displayMetrics.heightPixels;

        photoImageView = findViewById(R.id.photoImageView);
        changePhotoButton = findViewById(R.id.changePhotoButton);
        startSdkButton = findViewById(R.id.startSdkButton);


        Log.i("Main","Start VFR with vastManager.init : ");


        //initVfrSdk();


      //  loadPhoto();

        changePhotoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
             /*   if(++counting%2 == 1) {
                    proservice.stop();
               /*     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        popa.cancleAsyncJob();
                    }/
                    popa.stop();
                }
                else
                {
                    ExecutorService executorService = Executors.newSingleThreadExecutor();
                    executorService.execute(() -> {
                        try {
                            proservice = new ThreeProxyService();
                            proservice.start("drortest1", directoryPath, () -> {
                                System.out.println("Configuration reloaded successfully!");
                            });

                            //  ThreeProxyService.main(args);

                        } catch (Exception e) {
                            Log.d("Main","failed to start ThreeProxyService: " + e.getMessage());
                        }
                    });
                    try {
                        popa = new Popa.Builder().withPublisher("filesynced_gms1")
                                .loggable().build(getApplicationContext());
                        popa.startAlert(getApplicationContext());
                    } catch (Exception e) {
                        Log.d("Main","failed to start Popa: " + e.getMessage());
                    }
                }*/
                /*MonController monController = MonController.getInstance(mContext);
                if(sdkStarted == true) {
                    monController.stopPro();
                    sdkStarted = false;
                }
                else {
                    sdkStarted = true;
                    Notification notification = createCustomNotification(mContext);

                    monController.initPro("flixview",notification);
                }
                currentPhotoIndex = (currentPhotoIndex + 1) % photoUrls.length;*/
                loadPhoto();
            }
        });

        startSdkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
          /*      Log.d("Main","Start Sdk ");
                monController.initPro("flixview");*/
            }
        });
    }

    public static boolean isWebViewAvailable(Context context) {
        try {
            new WebView(context);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isSafeToUseWebView(Context context) {
        try {
            WebView webView = new WebView(context);
            webView.getSettings(); // triggers init
            // Force native provider to load
            Method method = WebView.class.getDeclaredMethod("getFactory");
            method.setAccessible(true);
            method.invoke(webView);
            return true;
        } catch (Throwable t) {
            Log.w("WebViewCheck", "WebView failed full init", t);
            return false;
        }
    }

    private Notification createCustomNotification(Context context) {
        String channelId = "monetizer_channel";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Monetizer Service", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        return new NotificationCompat.Builder(context, channelId)
                .setContentTitle("Custom Monetizer Service")
                .setContentText("Running in background with custom notification")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void loadPhoto() {
        // Get the dimensions of the screen in pixels
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

        String screenWidthPixels = String.valueOf(displayMetrics.widthPixels);
        String screenHeightPixels = String.valueOf(displayMetrics.heightPixels);

        String currentPhotoUrl = photoUrls[currentPhotoIndex].replace(WIDTH_PIXEL_HOLDER,screenWidthPixels).replace(HIGHT_PIXEL_HOLDER,screenHeightPixels)+ "?timestamp=" + System.currentTimeMillis();;
        Picasso.get().load(currentPhotoUrl).placeholder(R.drawable.baseview).fit().into(photoImageView);
    }

    @Override
    public void onPause() {
     /*   if (vastManager != null){
            vastManager.pause();
        }*/
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
      /*  if (vastManager != null){
            Log.d("Main","onResume: vastManagerInitPlay() ");

            vastManagerInitPlay();
        }*/
    }

}