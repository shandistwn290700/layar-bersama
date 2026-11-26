// Shared by tv.html and control.html: server clock estimate, WebSocket connection, video sync.

const Clock = {
  offset: 0, // server time minus local time, ms
  samples: [],
  now() { return Date.now() + this.offset; },
  add(c, s) {
    const t = Date.now();
    this.samples.push({ rtt: t - c, off: s + (t - c) / 2 - t });
    if (this.samples.length > 10) this.samples.shift();
    // The sample with the shortest round trip is the most accurate.
    this.offset = this.samples.reduce((a, b) => (b.rtt < a.rtt ? b : a)).off;
  },
};

function connectSocket({ hello, onMessage, onStatus }) {
  let ws, pingTimer;
  const ping = () => ws.readyState === 1 && ws.send(JSON.stringify({ t: 'ping', c: Date.now() }));
  const open = () => {
    ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
    ws.onopen = () => {
      onStatus(true);
      ws.send(JSON.stringify(hello()));
      for (let i = 0; i < 6; i++) setTimeout(ping, i * 250);
      pingTimer = setInterval(ping, 15000);
    };
    ws.onmessage = (e) => {
      const m = JSON.parse(e.data);
      if (m.t === 'pong') Clock.add(m.c, m.s);
      else onMessage(m);
    };
    ws.onclose = () => {
      clearInterval(pingTimer);
      onStatus(false);
      setTimeout(open, 2000);
    };
  };
  open();
  return {
    send: (msg) => ws && ws.readyState === 1 && ws.send(JSON.stringify(msg)),
    reconnect: () => ws && ws.close(),
  };
}

// Where the video should be right now, per the shared state. Needs the element's duration.
function videoTarget(el, state) {
  if (el.readyState < 1) return null; // metadata not loaded yet
  // Some files (e.g. browser-recorded WebM) report an unknown duration; sync them without end handling.
  const d = Number.isFinite(el.duration) && el.duration > 0 ? el.duration : Infinity;
  const v = state.video;
  let time = v.pos + (v.playing ? (Clock.now() - v.at) / 1000 : 0);
  let play = v.playing;
  if (time < 0) { time = 0; play = false; } // scheduled start not reached yet
  if (d !== Infinity) {
    if (state.loop) time %= d;
    else if (time >= d - 0.05) { time = Math.max(0, d - 0.05); play = false; }
  }
  return { time, play, duration: d };
}

// Nudge a <video> element toward the shared position: small drift is fixed by speed, large by seeking.
function syncVideo(el, state) {
  const t = videoTarget(el, state);
  if (!t) return;
  el.loop = !!state.loop;
  let diff = el.currentTime - t.time;
  if (state.loop && t.duration !== Infinity && Math.abs(diff) > t.duration / 2) diff -= Math.sign(diff) * t.duration;
  if (!t.play) {
    if (!el.paused) el.pause();
    if (Math.abs(diff) > 0.1 && !el.seeking) el.currentTime = t.time;
    el.playbackRate = 1;
    return;
  }
  if (Math.abs(diff) > 1) {
    if (!el.seeking) el.currentTime = t.time;
    el.playbackRate = 1;
  } else if (Math.abs(diff) > 0.03) {
    // Speed up or slow down by up to 10%, proportional to how far off we are.
    el.playbackRate = 1 - Math.max(-0.1, Math.min(0.1, diff * 0.3));
  } else {
    el.playbackRate = 1;
  }
  if (el.paused) {
    el.play().catch(() => {
      // Browsers block autoplay with sound until the user presses a button; fall back to muted.
      el.muted = true;
      el.play().catch(() => {});
    });
  }
}
