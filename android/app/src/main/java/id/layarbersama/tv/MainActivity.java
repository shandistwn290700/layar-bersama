package id.layarbersama.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Shows the Layar Bersama TV page full screen. Back (or Menu) on the remote opens the settings screen.
 */
public class MainActivity extends Activity {
    static final String PREFS = "layar";
    static final String KEY_SERVER = "server";
    static final String KEY_NAME = "name";
    static final String KEY_AUTOSTART = "autostart";
    private static final long RETRY_MS = 5000;

    private SharedPreferences prefs;
    private WebView web;
    private boolean onSetupScreen;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = () -> { if (web != null) web.loadUrl(tvUrl()); };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (server().isEmpty()) showSetup(); else showDisplay();
    }

    private String server() { return prefs.getString(KEY_SERVER, ""); }
    private String tvName() { return prefs.getString(KEY_NAME, "TV1"); }

    private String tvUrl() {
        String base = server().replaceAll("/+$", "");
        try {
            return base + "/tv?app=1&name=" + URLEncoder.encode(tvName(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return base + "/tv?app=1";
        }
    }

    // ---------- Display ----------

    private void showDisplay() {
        onSetupScreen = false;
        destroyWeb();
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // videos may start with sound, no button press
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // stay inside the app
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                showOffline(view, String.valueOf(error.getDescription()));
            }
        });
        setContentView(web);
        hideSystemBars();
        web.loadUrl(tvUrl());
    }

    private void showOffline(WebView view, String reason) {
        String html = "<html><body style='margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;"
                + "justify-content:center;background:#000;color:#bbb;font-family:sans-serif;text-align:center'>"
                + "<div style='font-size:4vw'>Tidak bisa terhubung ke server</div>"
                + "<div style='font-size:2vw;color:#777;margin-top:2vh'>" + escape(server()) + "<br>" + escape(reason)
                + "<br><br>Mencoba lagi otomatis&hellip; Tekan Kembali untuk membuka pengaturan.</div></body></html>";
        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_MS);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void destroyWeb() {
        handler.removeCallbacks(retry);
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
    }

    // ---------- Settings ----------

    private void showSetup() {
        onSetupScreen = true;
        destroyWeb();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(48);
        box.setPadding(pad, dp(32), pad, dp(32));
        box.setBackgroundColor(Color.rgb(21, 21, 20));

        TextView title = text("Layar Bersama: pengaturan TV", 26, Color.WHITE);
        box.addView(title);

        box.addView(label("Alamat server"));
        EditText serverInput = input(server().isEmpty() ? "https://" : server(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setHint("https://layar.contoh.com atau http://192.168.1.10:3080");
        box.addView(serverInput);

        box.addView(label("Nama TV (contoh: TV1, TV2)"));
        EditText nameInput = input(tvName(), InputType.TYPE_CLASS_TEXT);
        box.addView(nameInput);

        CheckBox autostart = new CheckBox(this);
        autostart.setText("Buka otomatis saat TV dinyalakan");
        autostart.setTextColor(Color.WHITE);
        autostart.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        autostart.setChecked(prefs.getBoolean(KEY_AUTOSTART, true));
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(-2, -2);
        cbLp.topMargin = dp(16);
        box.addView(autostart, cbLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(20);
        box.addView(buttons, rowLp);

        Button save = new Button(this);
        save.setText("Simpan & tampilkan");
        save.setOnClickListener(v -> {
            String url = normalizeUrl(serverInput.getText().toString());
            if (url == null) {
                serverInput.setError("Isi alamat server, misalnya https://layar.contoh.com");
                serverInput.requestFocus();
                return;
            }
            String name = nameInput.getText().toString().trim();
            prefs.edit()
                    .putString(KEY_SERVER, url)
                    .putString(KEY_NAME, name.isEmpty() ? "TV1" : name)
                    .putBoolean(KEY_AUTOSTART, autostart.isChecked())
                    .apply();
            showDisplay();
        });
        buttons.addView(save);

        Button overlay = new Button(this);
        overlay.setText("Izinkan buka otomatis");
        overlay.setOnClickListener(v -> openOverlaySettings());
        LinearLayout.LayoutParams ovLp = new LinearLayout.LayoutParams(-2, -2);
        ovLp.leftMargin = dp(16);
        buttons.addView(overlay, ovLp);
        boolean needsOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this);
        overlay.setVisibility(needsOverlay ? View.VISIBLE : View.GONE);

        TextView help = text("Tekan tombol Kembali di remote kapan saja untuk membuka layar ini lagi.\n"
                + (needsOverlay ? "Agar app terbuka sendiri setelah TV menyala, tekan \"Izinkan buka otomatis\" lalu aktifkan izinnya." : ""),
                15, Color.rgb(160, 160, 155));
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(-1, -2);
        helpLp.topMargin = dp(20);
        box.addView(help, helpLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(21, 21, 20));
        scroll.addView(box);
        setContentView(scroll);
        (server().isEmpty() ? serverInput : save).requestFocus();
    }

    /** Adds a scheme when missing: http for plain IP addresses (local network), https otherwise. */
    static String normalizeUrl(String raw) {
        String s = raw.trim();
        if (s.isEmpty() || s.equals("https://") || s.equals("http://")) return null;
        if (!s.matches("(?i)^https?://.*")) {
            boolean ip = s.matches("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$");
            s = (ip ? "http://" : "https://") + s;
        }
        return s.replaceAll("/+$", "");
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "TV ini tidak menyediakan pengaturan izin tersebut.", Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 16, Color.rgb(190, 190, 185));
        t.setPadding(0, dp(18), 0, dp(4));
        return t;
    }

    private EditText input(String value, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(110, 110, 105));
        e.setGravity(Gravity.CENTER_VERTICAL);
        return e;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- Lifecycle & remote keys ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean settingsKey = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_SETTINGS;
        if (settingsKey) {
            if (!onSetupScreen) {
                showSetup();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK && !server().isEmpty()) {
                showDisplay();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !onSetupScreen) hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyWeb();
        super.onDestroy();
    }
}
