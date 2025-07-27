package io.hopmonsdk.util;

import android.util.Log;

import io.hopmonsdk.Hopmn;

public class LogUtils {

    public static boolean isDebug(String tag) {
        return isLoggable(tag, Log.DEBUG);
    }

    public static boolean isInfo(String tag) {
        return isLoggable(tag, Log.INFO);
    }

    public static boolean isLoggable(String tag, int level) {
        try {
            Hopmn acp = Hopmn.getInstance(true);
            return (acp != null && acp.isLoggable()) || Log.isLoggable(tag, level);
        }
        catch(Exception ex){
            Log.e(tag, "Failed to getInstance on MoneytiserService onCreate: ", ex);
            return false;
        }
    }

    public static void v(String tag, String msg) {
        if (isLoggable(tag, Log.VERBOSE)) {
            Log.v(tag, msg);
        }
    }

    public static void v(String tag, String msg, Throwable tr) {
        if (isLoggable(tag, Log.VERBOSE)) {
            Log.v(tag, msg, tr);
        }
    }

    public static void d(String tag, String msg, Object... args) {
        if (isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, String.format(msg, args));
        }
    }

    public static void d(String tag, String msg, Throwable tr, Object... args) {
        if (isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, String.format(msg, args), tr);
        }
    }

    public static void i(String tag, String msg, Object... args) {
        if (isLoggable(tag, Log.INFO)) {
            Log.i(tag, String.format(msg, args));
        }
    }

    public static void i(String tag, String msg, Throwable tr, Object... args) {
        if (isLoggable(tag, Log.INFO)) {
            Log.i(tag, String.format(msg, args), tr);
        }
    }

    public static void w(String tag, String msg, Object... args) {
        if (isLoggable(tag, Log.WARN)) {
            Log.w(tag, String.format(msg, args));
        }
    }

    public static void w(String tag, String msg, Throwable tr, Object... args) {
        if (isLoggable(tag, Log.WARN)) {
            Log.w(tag, String.format(msg, args), tr);
        }
    }

    public static void e(String tag, String msg, Object... args) {
        if (Log.isLoggable(tag, Log.ERROR)) {
            Log.e(tag, String.format(msg, args));
        }
    }

    public static void e(String tag, String msg, Throwable tr, Object... args) {
        if (isLoggable(tag, Log.ERROR)) {
            Log.e(tag, String.format(msg, args), tr);
        }
    }

    private LogUtils() {}
}
