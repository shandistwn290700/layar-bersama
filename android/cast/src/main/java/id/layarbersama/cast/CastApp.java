package id.layarbersama.cast;

import android.app.Application;

/** Installs the crash reporter before any activity or service starts. */
public class CastApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashReporter.install(this);
    }
}
