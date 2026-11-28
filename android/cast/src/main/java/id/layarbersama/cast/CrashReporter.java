package id.layarbersama.cast;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Saves crashes (and caught errors) to a file and sends them to the Layar Bersama server
 * (POST /api/crash), so problems can be diagnosed without connecting the phone to a computer.
 */
final class CrashReporter {
    private static final String FILE = "last-crash.txt";
    private static final String PENDING = "crash-pending";

    private CrashReporter() { }

    static void install(Context ctx) {
        Context app = ctx.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                String text = build(app, "CRASH di thread " + thread.getName(), error);
                save(app, text);
                // Give the upload a moment before the process dies.
                Thread t = new Thread(() -> upload(app, text));
                t.start();
                t.join(3000);
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
        // A crash from the last run that could not be sent yet.
        if (prefs(app).getBoolean(PENDING, false)) {
            new Thread(() -> { String last = lastCrash(app); if (last != null) upload(app, last); }).start();
        }
    }

    /** For errors the app caught and survived. */
    static void report(Context ctx, String where, Throwable error) {
        Context app = ctx.getApplicationContext();
        String text = build(app, "ERROR (app tetap jalan) di " + where, error);
        new Thread(() -> upload(app, text)).start();
    }

    static String lastCrash(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static void clearLastCrash(Context ctx) {
        new File(ctx.getFilesDir(), FILE).delete();
    }

    private static String build(Context ctx, String title, Throwable error) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("Layar Bersama Cast - " + title);
        pw.println("HP: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        try {
            pw.println("Versi app: " + ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName);
        } catch (Exception ignored) { }
        pw.println("Status terakhir: " + CastService.status);
        error.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static void save(Context ctx, String text) {
        try (OutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), FILE))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        prefs(ctx).edit().putBoolean(PENDING, true).commit();
    }

    private static void upload(Context ctx, String text) {
        String server = ctx.getSharedPreferences("cast", Context.MODE_PRIVATE).getString("server", "");
        if (!server.startsWith("http")) return;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(server + "/api/crash").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            try (OutputStream out = c.getOutputStream()) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() < 300) prefs(ctx).edit().putBoolean(PENDING, false).apply();
            c.disconnect();
        } catch (Exception ignored) { }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("crash", Context.MODE_PRIVATE);
    }
}
