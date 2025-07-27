package io.hopmonsdk.support;

import android.content.Context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;

import io.hopmonsdk.util.FileUtils;
import io.hopmonsdk.util.LogUtils;

public class ConfigManager {

    private static final String TAG = ConfigManager.class.getSimpleName();
    private static final String CONFIG_FILE = "hopmnpro.cfg";
    private static final String NO_AUTH = "auth none";

    private Context mContext;

    private boolean mEnableLogging = false;

    public ConfigManager(Context context) {
        mContext = context;
    }

    public void setEnableLogging(boolean enableLogging) {
        this.mEnableLogging = enableLogging;
    }

    public File writeToFile(String directive) {
        // Returns a File representing an internal directory for your app.
        final File configFile = new File(mContext.getFilesDir(), CONFIG_FILE);
        try {
            if (!configFile.exists() || configFile.delete()) {
                if (configFile.createNewFile()) {
                    String cleanedDirective = clean(directive);
                    LogUtils.d(TAG, "Write new directive '%s' to hopmnproxy configuration file", cleanedDirective);
                    // Save your stream, don't forget to flush() it before closing it.
                    FileOutputStream fOut = new FileOutputStream(configFile);
                    BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fOut));
                    if (mEnableLogging) {
                        File logFile = new File(mContext.getFilesDir(), "log");
                        LogUtils.d(TAG, "Enable logging to file %s", logFile);
                        bw.append(String.format("log %s D", logFile.getAbsolutePath()));
                        bw.newLine();
                    }
                    /*bw.append(NO_AUTH);
                    bw.newLine();*/
                    bw.append(cleanedDirective);
                    bw.flush();
                    bw.close();
                    fOut.flush();
                    fOut.close();
                    LogUtils.d(TAG, "hopmnproxy config file wrote %s", CONFIG_FILE);
                    LogUtils.d(TAG, "Config:\n%s\n=========", FileUtils.toString(configFile));
                } else {
                    LogUtils.w(TAG, "opmnproxy config file creation failed");
                }
            } else {
                LogUtils.w(TAG, "hopmnproxy config file cannot deleted");
            }

        } catch (IOException e) {
            LogUtils.e(TAG, "File write failed: ", e);
        }
        return configFile;
    }

    private String clean(String data) {
        return data.replace("config:", "proxy ")
                .replaceFirst(",$", "")
                .replaceAll(",", ":");
    }

}
