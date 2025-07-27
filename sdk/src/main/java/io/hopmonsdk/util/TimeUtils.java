package io.hopmonsdk.util;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class TimeUtils {

    /**
     * Converts time (in milliseconds) to human-readable format
     * "{@literal <}dd:{@literal >}hh:mm:ss"
     */
    public static String millisToShortDHMS(long duration) {
        String res = "";
        long days = TimeUnit.MILLISECONDS.toDays(duration);
        long hours = TimeUnit.MILLISECONDS.toHours(duration)
                - TimeUnit.DAYS.toHours(TimeUnit.MILLISECONDS.toDays(duration));
        long minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
                - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(duration));
        long seconds = TimeUnit.MILLISECONDS.toSeconds(duration)
                - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(duration));
        if (days == 0) {
            res = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            res = String.format(Locale.US, "%dd%02d:%02d:%02d", days, hours, minutes, seconds);
        }
        return res;
    }

    private TimeUtils() {}

}
