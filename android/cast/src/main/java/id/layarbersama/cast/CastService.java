package id.layarbersama.cast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Captures the phone screen and streams it over WebRTC to every TV connected to the Layar Bersama server.
 * The server only relays signaling; video goes straight from the phone to each TV.
 */
public class CastService extends Service {
    static final String ACTION_START = "start";
    static final String ACTION_STOP = "stop";
    static final String EXTRA_RESULT_DATA = "resultData";
    static final String EXTRA_SERVER = "server";
    static final String EXTRA_PIN = "pin";
    static final String EXTRA_NAME = "name";

    private static final String CHANNEL = "cast";
    private static final int NOTIFICATION_ID = 1;
    private static final int MAX_SIDE = 1600;     // longest side of the cast picture, in pixels
    private static final int FPS = 30;
    private static final int MAX_BITRATE = 6_000_000;

    /** Read by the activity to show what is happening. */
    static volatile String status = "Belum cast.";
    static volatile boolean running = false;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(); // all WebRTC work runs here, in order
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, PeerConnection> peers = new HashMap<>();
    private final Map<String, String> peerNames = new HashMap<>();
    private final List<PeerConnection.IceServer> iceServers = new ArrayList<>();

    private OkHttpClient http;
    private WebSocket ws;
    private EglBase egl;
    private PeerConnectionFactory factory;
    private ScreenCapturerAndroid capturer;
    private SurfaceTextureHelper textureHelper;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private String server, pin, name;
    private boolean stopping;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void step(String what) { CrashReporter.step(this, what); }

    /**
     * Runs work on the WebRTC thread. Callbacks (WebSocket, screen capture) can still arrive after the
     * service has shut down; those are dropped instead of crashing the app, and any error is reported.
     */
    private void run(Runnable work) {
        try {
            exec.execute(() -> {
                try {
                    work.run();
                } catch (Throwable t) {
                    CrashReporter.report(this, "CastService", t);
                    setStatus("Terjadi error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // service already stopped
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            run(() -> shutdown(true));
            return START_NOT_STICKY;
        }
        step("onStartCommand START (running=" + running + ")");
        if (running) return START_NOT_STICKY; // already casting; ignore a second start
        // Android requires the foreground notification before the screen capture starts.
        try {
            Notification n = buildNotification("Menyiapkan cast…");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            step("startForeground OK");
        } catch (RuntimeException e) {
            step("startForeground GAGAL: " + e);
            CrashReporter.report(this, "startForeground", e);
            status = "Android menolak memulai cast: " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        stopping = false;
        server = intent.getStringExtra(EXTRA_SERVER);
        pin = intent.getStringExtra(EXTRA_PIN);
        name = intent.getStringExtra(EXTRA_NAME);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        run(() -> {
            try {
                startCapture(resultData);
                connect();
            } catch (Throwable e) {
                CrashReporter.report(this, "startCapture", e);
                setStatus("Gagal memulai cast: " + e.getMessage());
                shutdown(false);
            }
        });
        return START_NOT_STICKY;
    }

    // ---------- Screen capture ----------

    private void startCapture(Intent resultData) {
        iceServers.clear();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        step("PeerConnectionFactory.initialize");
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this)
                .createInitializationOptions());
        step("EglBase.create");
        egl = EglBase.create();
        step("createPeerConnectionFactory");
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();

        step("ScreenCapturerAndroid dibuat");
        capturer = new ScreenCapturerAndroid(resultData, new MediaProjection.Callback() {
            @Override
            public void onStop() { // the user ended sharing from the system UI
                run(() -> shutdown(true));
            }
        });
        step("SurfaceTextureHelper + VideoSource");
        textureHelper = SurfaceTextureHelper.create("ScreenCapture", egl.getEglBaseContext());
        videoSource = factory.createVideoSource(true);
        capturer.initialize(textureHelper, this, videoSource.getCapturerObserver());

        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(dm.widthPixels, dm.heightPixels));
        int w = ((int) (dm.widthPixels * scale)) & ~1; // encoders want even sizes
        int h = ((int) (dm.heightPixels * scale)) & ~1;
        step("startCapture " + w + "x" + h + " (layar " + dm.widthPixels + "x" + dm.heightPixels + ")");
        capturer.startCapture(w, h, FPS);
        step("startCapture OK");
        videoTrack = factory.createVideoTrack("screen0", videoSource);
    }

    // ---------- Signaling ----------

    private void connect() {
        if (stopping) return;
        setStatus("Menghubungkan ke server…");
        step("connect " + server);
        if (http == null) http = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
        String url = server.replaceFirst("(?i)^http", "ws") + "/ws";
        ws = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                step("WebSocket terbuka");
                send(obj("t", "hello", "role", "control", "name", name, "pin", pin));
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                run(() -> {
                    try { handle(new JSONObject(text)); } catch (JSONException ignored) { }
                });
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) { step("WebSocket ditutup " + code); run(() -> onDisconnected()); }

            @Override
            public void onFailure(WebSocket socket, Throwable t, Response response) { step("WebSocket gagal: " + t); run(() -> onDisconnected()); }
        });
    }

    private void onDisconnected() {
        if (stopping) return;
        closePeers();
        setStatus("Koneksi ke server terputus, menyambung ulang…");
        main.postDelayed(() -> run(this::connect), 3000);
    }

    private void handle(JSONObject m) throws JSONException {
        switch (m.optString("t")) {
            case "config":
                readIceServers(m.optJSONArray("iceServers"));
                break;
            case "auth":
                step("auth ok=" + m.optBoolean("ok"));
                if (m.optBoolean("ok")) {
                    send(obj("t", "cast-start", "name", name));
                    setStatus("Menyambungkan ke TV…");
                } else {
                    setStatus("PIN salah. Periksa PIN lalu coba lagi.");
                    shutdown(true);
                }
                break;
            case "cast-peer":
                step("cast-peer " + m.optString("name"));
                addPeer(m.getString("id"), m.optString("name", "TV"));
                break;
            case "cast-peer-leave":
                removePeer(m.getString("id"));
                updateStatus();
                break;
            case "signal":
                onSignal(m.getString("from"), m.getJSONObject("data"));
                break;
            case "cast-ended":
                setStatus("Cast berhenti karena perangkat lain mulai cast.");
                shutdown(true);
                break;
            default:
                break;
        }
    }

    private void readIceServers(JSONArray list) throws JSONException {
        if (list == null || list.length() == 0) return;
        iceServers.clear();
        for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.getJSONObject(i);
            List<String> urls = new ArrayList<>();
            Object u = s.get("urls");
            if (u instanceof JSONArray) {
                for (int j = 0; j < ((JSONArray) u).length(); j++) urls.add(((JSONArray) u).getString(j));
            } else {
                urls.add(String.valueOf(u));
            }
            PeerConnection.IceServer.Builder b = PeerConnection.IceServer.builder(urls);
            if (s.has("username")) b.setUsername(s.getString("username"));
            if (s.has("credential")) b.setPassword(s.getString("credential"));
            iceServers.add(b.createIceServer());
        }
    }

    // ---------- Peers (one per TV) ----------

    private void addPeer(String id, String tvName) {
        removePeer(id);
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(iceServers);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        PeerConnection pc = factory.createPeerConnection(cfg, new PeerObserver(id));
        if (pc == null) return;
        pc.addTrack(videoTrack, Collections.singletonList("cast"));
        peers.put(id, pc);
        peerNames.put(id, tvName);
        pc.createOffer(new SdpAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                run(() -> {
                    if (peers.get(id) != pc) return;
                    step("offer dibuat untuk " + tvName);
                    pc.setLocalDescription(new SdpAdapter(), sdp);
                    sendSignal(id, obj("sdp", obj("type", "offer", "sdp", sdp.description)));
                });
            }
        }, new MediaConstraints());
        updateStatus();
    }

    private void onSignal(String from, JSONObject data) throws JSONException {
        PeerConnection pc = peers.get(from);
        if (pc == null) return;
        if (data.has("sdp")) {
            JSONObject sdp = data.getJSONObject("sdp");
            if (!"answer".equals(sdp.optString("type"))) return;
            step("answer diterima dari " + peerNames.get(from));
            pc.setRemoteDescription(new SdpAdapter() {
                @Override
                public void onSetSuccess() { step("answer dipasang, encoder mulai"); run(() -> limitBitrate(pc)); }
            }, new SessionDescription(SessionDescription.Type.ANSWER, sdp.getString("sdp")));
        } else if (data.has("candidate")) {
            JSONObject c = data.getJSONObject("candidate");
            pc.addIceCandidate(new IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.getString("candidate")));
        }
    }

    private void limitBitrate(PeerConnection pc) {
        if (!peers.containsValue(pc)) return;
        for (RtpSender s : pc.getSenders()) {
            RtpParameters p = s.getParameters();
            for (RtpParameters.Encoding e : p.encodings) e.maxBitrateBps = MAX_BITRATE;
            p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION;
            s.setParameters(p);
        }
    }

    private void removePeer(String id) {
        PeerConnection pc = peers.remove(id);
        peerNames.remove(id);
        if (pc != null) pc.dispose();
    }

    private void closePeers() {
        for (String id : new ArrayList<>(peers.keySet())) removePeer(id);
    }

    private void updateStatus() {
        List<String> connected = new ArrayList<>();
        for (Map.Entry<String, PeerConnection> e : peers.entrySet()) {
            if (e.getValue().connectionState() == PeerConnection.PeerConnectionState.CONNECTED) {
                connected.add(peerNames.get(e.getKey()));
            }
        }
        if (!connected.isEmpty()) {
            setStatus("Sedang cast ke " + connected.size() + " TV: " + String.join(", ", connected));
        } else if (!peers.isEmpty()) {
            setStatus("Menyambungkan ke TV…");
        } else {
            setStatus("Tidak ada TV yang terhubung ke server.");
        }
    }

    private class PeerObserver implements PeerConnection.Observer {
        private final String id;
        PeerObserver(String id) { this.id = id; }

        @Override
        public void onIceCandidate(IceCandidate c) {
            run(() -> sendSignal(id,
                    obj("candidate", obj("candidate", c.sdp, "sdpMid", c.sdpMid, "sdpMLineIndex", c.sdpMLineIndex))));
        }

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState s) {
            step("koneksi ke TV " + peerNames.get(id) + ": " + s);
            run(CastService.this::updateStatus);
        }

        @Override public void onSignalingChange(PeerConnection.SignalingState s) { }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) { }
        @Override public void onIceConnectionReceivingChange(boolean receiving) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
        @Override public void onAddStream(MediaStream stream) { }
        @Override public void onRemoveStream(MediaStream stream) { }
        @Override public void onDataChannel(DataChannel channel) { }
        @Override public void onRenegotiationNeeded() { }
        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) { }
    }

    private class SdpAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) { step("SDP create gagal: " + error); }
        @Override public void onSetFailure(String error) { step("SDP set gagal: " + error); }
    }

    // ---------- Helpers ----------

    private void send(JSONObject msg) {
        if (ws != null) ws.send(msg.toString());
    }

    private void sendSignal(String to, JSONObject data) {
        send(obj("t", "signal", "to", to, "data", data));
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) { }
        return o;
    }

    private void setStatus(String s) {
        status = s;
        main.post(() -> {
            if (running) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(NOTIFICATION_ID, buildNotification(s));
            }
        });
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Cast", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, CastActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CastService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Layar Bersama Cast")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Hentikan", stop).build())
                .build();
    }

    /** Stops capture and closes everything. Runs on the exec thread. */
    private void shutdown(boolean tellServer) {
        if (stopping && !running) return;
        stopping = true;
        step("shutdown (status: " + status + ")");
        if (tellServer) send(obj("t", "cast-stop"));
        closePeers();
        if (ws != null) { ws.close(1000, null); ws = null; }
        if (videoTrack != null) { videoTrack.dispose(); videoTrack = null; }
        if (capturer != null) {
            capturer.stopCapture();
            capturer.dispose();
            capturer = null;
        }
        if (videoSource != null) { videoSource.dispose(); videoSource = null; }
        if (textureHelper != null) { textureHelper.dispose(); textureHelper = null; }
        if (factory != null) { factory.dispose(); factory = null; }
        if (egl != null) { egl.release(); egl = null; }
        running = false;
        CrashReporter.endSession(this);
        if (status.startsWith("Sedang") || status.startsWith("Menyambung") || status.startsWith("Menghubung")
                || status.startsWith("Menyiapkan") || status.startsWith("Tidak ada")) {
            status = "Cast dihentikan.";
        }
        main.post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    @Override
    public void onDestroy() {
        run(() -> { if (running) shutdown(true); });
        exec.shutdown();
        super.onDestroy();
    }
}
