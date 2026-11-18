package com.example.trafficwidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Refresh log: one line per refresh trigger, written to internal storage.
 *
 * <p>Format: {@code [dd日HH:mm:ss]<触发来源>，<刷新结果>}. The file keeps the latest
 * {@link #KEEP_LINES} lines; instead of rewriting the file on every append it is trimmed
 * once when the line count reaches {@link #TRIM_AT}, which bounds both flash wear and the
 * time spent in the broadcast/refresh path.</p>
 */
final class WidgetLogger {

    private static final String TAG = "TrafficWidget";
    private static final String FILE_NAME = "widget_refresh.log";
    private static final String PREFS = "traffic_widget_log";
    private static final String KEY_LINES = "line_count";
    private static final String KEY_LAST_SKIP = "last_skip_at";
    private static final int KEEP_LINES = 100;
    private static final int TRIM_AT = 150;
    /** While the screen stays off the heartbeat fires repeatedly; log skips at most hourly. */
    private static final long SKIP_INTERVAL_MS = 60L * 60L * 1000L;

    private static final Object LOCK = new Object();

    private WidgetLogger() {
    }

    /** Appends one record, prefixed with the current time. Never throws. */
    static void log(Context context, String message) {
        synchronized (LOCK) {
            appendLocked(context, message);
        }
    }

    /**
     * Same as {@link #log} but at most once per hour. Used for "skipped because the screen
     * is off" lines so that an idle night cannot push every real refresh out of the log.
     */
    static void logSkip(Context context, String message) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            SharedPreferences preferences =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long last = preferences.getLong(KEY_LAST_SKIP, 0L);
            if (now - last < SKIP_INTERVAL_MS) {
                return;
            }
            preferences.edit().putLong(KEY_LAST_SKIP, now).apply();
            appendLocked(context, message);
        }
    }

    private static void appendLocked(Context context, String message) {
        String line = "[" + timestamp() + "]" + message;
        File file = new File(context.getFilesDir(), FILE_NAME);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            Log.w(TAG, "Unable to write widget log", e);
            return;
        }

        SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int lines = preferences.getInt(KEY_LINES, 0) + 1;
        if (lines >= TRIM_AT) {
            lines = trim(file);
        }
        preferences.edit().putInt(KEY_LINES, lines).apply();
    }

    /** Returns every stored line, oldest first, for the settings dialog. */
    static String readLogs(Context context) {
        synchronized (LOCK) {
            List<String> lines = readLines(new File(context.getFilesDir(), FILE_NAME));
            if (lines.isEmpty()) {
                return context.getString(R.string.refresh_logs_empty);
            }
            StringBuilder builder = new StringBuilder();
            for (String line : lines) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
            return builder.toString();
        }
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Unable to delete widget log");
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().clear().apply();
        }
    }

    /** Keeps only the newest {@link #KEEP_LINES} lines; returns how many remain. */
    private static int trim(File file) {
        List<String> lines = readLines(file);
        int start = Math.max(0, lines.size() - KEEP_LINES);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
            for (int i = start; i < lines.size(); i++) {
                writer.write(lines.get(i));
                writer.newLine();
            }
        } catch (IOException e) {
            Log.w(TAG, "Unable to trim widget log", e);
            return lines.size();
        }
        return lines.size() - start;
    }

    private static List<String> readLines(File file) {
        List<String> lines = new ArrayList<>();
        if (!file.exists()) {
            return lines;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Unable to read widget log", e);
        }
        return lines;
    }

    private static String timestamp() {
        return new SimpleDateFormat("dd日HH:mm:ss", Locale.CHINA).format(new Date());
    }
}
