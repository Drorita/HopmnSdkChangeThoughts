package io.hopmonsdk.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class FileUtils {

    public static String toString(InputStream is) throws IOException {
        String line;
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    public static String toString(String filePath) throws IOException {
        return toString(new File(filePath));
    }

    public static String toString(File fl) throws IOException {
        FileInputStream fin = new FileInputStream(fl);
        String ret = toString(fin);
        // Make sure you close all streams.
        fin.close();
        return ret;
    }

    private FileUtils() { }

}
