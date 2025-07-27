package io.hopmonsdk.task;

import android.os.AsyncTask;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.hopmonsdk.HopmnProxy;
import io.hopmonsdk.util.LogUtils;

public class ProxyAsyncTask extends AsyncTask<String, Void, Long> {

    private static final String TAG = "ProxyAsyncTask";

    private volatile boolean running;

    private volatile long startTime;
    
    private volatile long activityTime;

    @Override
    protected Long doInBackground(String... strings) {
        activityTime = 0;
        startTime = System.currentTimeMillis();
        running = true;
        List<String> args = new ArrayList<>(strings.length /*+ 1*/);
       // args.add("");
        args.addAll(Arrays.asList(strings));
        LogUtils.i(TAG, "Starting hopmnserver server %s", args);
        try {
            HopmnProxy.start(args.toArray(new String[0]));
        } finally {
            running = false;
        }
        LogUtils.i(TAG, "Released hopmn thread");
        activityTime = System.currentTimeMillis() - startTime;
        return activityTime;
    }

    @Override
    protected void onPostExecute(Long result) {
        LogUtils.i(TAG, "Executed hopmn async task for %ss", result/1000.0);
    }

    public boolean isRunning() {
        return running;
    }

    public long getUpTime() {
        return getUpTime(TimeUnit.MILLISECONDS);
    }

    public long getUpTime(TimeUnit unit) {
        return running ? TimeUnit.MILLISECONDS.convert(System.currentTimeMillis() - startTime, unit) : 0 ;
    }

}
