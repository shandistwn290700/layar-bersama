package id.layarbersama.cast;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
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
import java.util.List;

/**
 * Saves crashes (and caught errors) to a file and sends them to the Layar Bersama server
 * (POST /api/crash), so problems can be diagnosed without connecting the phone to a computer.
 * Native crashes (inside the video library) are picked up from Android's exit records on the next start.
 */
final class CrashReporter {
    static final String DEFAULT_SERVER = "https://layar.shandistwn.dpdns.org";
    private static final String FILE = "last-crash.txt";
    private static final String PENDING = "crash-pending";
    private static final String EXIT_CHECKED = "exit-checked-at";

    private CrashReporter() { }

    static void install(Context ctx) {
        Context app = ctx.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                String text = build(app, "CRASH di thread " + thread.getName(), error);
                save(app, text);
                // Give the upload a moment before the process dies; if it fails it is retried on the next start.
                Thread t = new Thread(() -> upload(app, text, 5000));
                t.start();
                t.join(6000);
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
        new Thread(() -> {
            if (prefs(app).getBoolean(PENDING, false)) {
                String last = lastCrash(app);
                if (last != null) upload(app, last, 20000);
            }
            reportPastExits(app);
        }).start();
    }

    /** Android 11+ remembers why the app's process ended, including native crashes the Java handler never sees. */
    private static void reportPastExits(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            ActivityManager am = ctx.getSystemService(ActivityManager.class);
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, 5);
            long checked = prefs(ctx).getLong(EXIT_CHECKED, 0);
            long newest = checked;
            for (ApplicationExitInfo e : exits) {
                if (e.getTimestamp() <= checked) continue;
                newest = Math.max(newest, e.getTimestamp());
                int r = e.getReason();
                if (r != ApplicationExitInfo.REASON_CRASH && r != ApplicationExitInfo.REASON_CRASH_NATIVE
                        && r != ApplicationExitInfo.REASON_ANR && r != ApplicationExitInfo.REASON_INITIALIZATION_FAILURE) {
                    continue;
                }
                String text = header(ctx, "PROSES BERHENTI (dari catatan Android)")
                        + "Waktu: " + new java.util.Date(e.getTimestamp())
                        + "\nAlasan: " + reasonName(r) + " (status " + e.getStatus() + ")"
                        + "\nKeterangan: " + e.getDescription()
                        + "\nProses: " + e.getProcessName() + "\n";
                if (r == ApplicationExitInfo.REASON_CRASH_NATIVE || r == ApplicationExitInfo.REASON_CRASH) save(ctx, text);
                upload(ctx, text, 20000);
            }
            prefs(ctx).edit().putLong(EXIT_CHECKED, newest).apply();
        } catch (Throwable ignored) { }
    }

    private static String reasonName(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_CRASH: return "CRASH (Java)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR (tidak merespons)";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            default: return String.valueOf(r);
        }
    }

    /** For errors the app caught and survived. */
    static void report(Context ctx, String where, Throwable error) {
        Context app = ctx.getApplicationContext();
        String text = build(app, "ERROR (app tetap jalan) di " + where, error);
        new Thread(() -> upload(app, text, 20000)).start();
    }

    /** Sends the saved crash again; returns true when the server accepted it. Call off the main thread. */
    static boolean resend(Context ctx) {
        String last = lastCrash(ctx);
        return last != null && upload(ctx, last, 20000);
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

    private static String header(Context ctx, String title) {
        StringBuilder sb = new StringBuilder("Layar Bersama Cast - ").append(title).append('\n');
        sb.append("HP: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        try {
            sb.append("Versi app: ").append(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName).append('\n');
        } catch (Exception ignored) { }
        sb.append("Status terakhir: ").append(CastService.status).append('\n');
        return sb.toString();
    }

    private static String build(Context ctx, String title, Throwable error) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.print(header(ctx, title));
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

    private static boolean upload(Context ctx, String text, int timeoutMs) {
        String server = ctx.getSharedPreferences("cast", Context.MODE_PRIVATE).getString("server", "");
        if (!server.startsWith("http")) server = DEFAULT_SERVER;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(server + "/api/crash").openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            try (OutputStream out = c.getOutputStream()) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            boolean ok = c.getResponseCode() < 300;
            if (ok) prefs(ctx).edit().putBoolean(PENDING, false).apply();
            c.disconnect();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("crash", Context.MODE_PRIVATE);
    }
}
