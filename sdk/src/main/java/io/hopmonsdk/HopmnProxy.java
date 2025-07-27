package io.hopmonsdk;

import android.util.Log;

public class HopmnProxy {

    static {
        try {
            System.loadLibrary("hopmnproxy");
            Log.d("HopmnProxy", "Library loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e("HopmnProxy", "Failed to load native library", e);
        }
    }

    // Native methods exposed by the .so
    public static native int start(String[] args);
    public static native void reload();
    public static native void stop();
}
