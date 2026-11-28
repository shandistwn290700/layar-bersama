package id.layarbersama.cast;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Settings and a start/stop button. The casting itself runs in {@link CastService}. */
public class CastActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private static final String PREFS = "cast";

    private SharedPreferences prefs;
    private EditText serverInput, pinInput, nameInput;
    private Button button;
    private TextView statusView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(24), pad, dp(24));

        TextView title = new TextView(this);
        String version = "";
        try { version = " " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        title.setText("Layar Bersama Cast" + version);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        box.addView(title);

        TextView intro = new TextView(this);
        intro.setText("Tampilkan layar HP ini secara langsung di semua TV Layar Bersama.");
        intro.setPadding(0, dp(6), 0, dp(8));
        box.addView(intro);

        box.addView(label("Alamat server"));
        serverInput = input(prefs.getString("server", CrashReporter.DEFAULT_SERVER), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setHint("https://layar.contoh.com");
        box.addView(serverInput);

        box.addView(label("PIN kontrol"));
        pinInput = input(prefs.getString("pin", ""), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(pinInput);

        box.addView(label("Nama perangkat ini"));
        nameInput = input(prefs.getString("name", "HP"), InputType.TYPE_CLASS_TEXT);
        box.addView(nameInput);

        button = new Button(this);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.topMargin = dp(20);
        box.addView(button, lp);
        button.setOnClickListener(v -> {
            if (CastService.running) stopCast(); else requestCapture();
        });

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusView.setPadding(0, dp(16), 0, 0);
        box.addView(statusView);

        String lastCrash = CrashReporter.lastCrash(this);
        if (lastCrash != null) {
            TextView crash = new TextView(this);
            String[] lines = lastCrash.split("\n");
            StringBuilder head = new StringBuilder("App sempat berhenti mendadak. Detailnya:\n");
            for (int i = 0; i < Math.min(lines.length, 14); i++) head.append('\n').append(lines[i]);
            crash.setText(head.toString());
            crash.setTextIsSelectable(true);
            crash.setTextColor(Color.rgb(224, 100, 92));
            crash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            crash.setPadding(0, dp(16), 0, 0);
            box.addView(crash);

            Button resend = new Button(this);
            resend.setText("Kirim laporan ke server");
            resend.setOnClickListener(v -> {
                resend.setEnabled(false);
                resend.setText("Mengirim…");
                new Thread(() -> {
                    boolean ok = CrashReporter.resend(this);
                    runOnUiThread(() -> {
                        resend.setText(ok ? "Laporan terkirim" : "Gagal mengirim, coba lagi");
                        resend.setEnabled(!ok);
                        if (ok) CrashReporter.clearLastCrash(this);
                    });
                }).start();
            });
            box.addView(resend);
        }

        TextView note = new TextView(this);
        note.setText("Android akan bertanya izin merekam layar setiap kali cast dimulai. "
                + "Suara HP tidak ikut terkirim. Cast bisa dihentikan dari notifikasi.");
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(16), 0, 0);
        box.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    private void render() {
        button.setText(CastService.running ? "Hentikan cast" : "Mulai cast");
        statusView.setText(CastService.status);
        boolean editable = !CastService.running;
        serverInput.setEnabled(editable);
        pinInput.setEnabled(editable);
        nameInput.setEnabled(editable);
    }

    private void requestCapture() {
        String server = normalizeUrl(serverInput.getText().toString());
        if (server == null) {
            serverInput.setError("Isi alamat server, misalnya https://layar.contoh.com");
            return;
        }
        String name = nameInput.getText().toString().trim();
        prefs.edit()
                .putString("server", server)
                .putString("pin", pinInput.getText().toString().trim())
                .putString("name", name.isEmpty() ? "HP" : name)
                .apply();
        serverInput.setText(server);
        CrashReporter.beginSession(this);
        CrashReporter.step(this, "tombol Mulai cast, membuka dialog izin rekam layar");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        CrashReporter.step(this, "hasil dialog izin: resultCode=" + resultCode + " data=" + (data != null));
        if (resultCode != RESULT_OK || data == null) {
            CrashReporter.endSession(this);
            CastService.status = "Izin merekam layar ditolak.";
            render();
            return;
        }
        Intent svc = new Intent(this, CastService.class)
                .setAction(CastService.ACTION_START)
                .putExtra(CastService.EXTRA_RESULT_DATA, data)
                .putExtra(CastService.EXTRA_SERVER, prefs.getString("server", ""))
                .putExtra(CastService.EXTRA_PIN, prefs.getString("pin", ""))
                .putExtra(CastService.EXTRA_NAME, prefs.getString("name", "HP"));
        CrashReporter.step(this, "memanggil startForegroundService");
        try {
            startForegroundService(svc);
        } catch (RuntimeException e) {
            CrashReporter.step(this, "startForegroundService GAGAL: " + e);
            CrashReporter.report(this, "startForegroundService", e);
            CastService.status = "Android menolak memulai cast: " + e.getMessage();
            render();
            return;
        }
        CrashReporter.step(this, "startForegroundService terkirim");
        CastService.status = "Menyiapkan cast…";
        render();
    }

    private void stopCast() {
        startService(new Intent(this, CastService.class).setAction(CastService.ACTION_STOP));
    }

    /** Adds a scheme when missing: http for plain IP addresses (local network), https otherwise. */
    static String normalizeUrl(String raw) {
        String s = raw.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("https://") || s.equalsIgnoreCase("http://")) return null;
        if (!s.matches("(?i)^https?://.*")) {
            boolean ip = s.matches("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$");
            s = (ip ? "http://" : "https://") + s;
        }
        return s.replaceAll("/+$", "");
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private EditText input(String value, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        return e;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
