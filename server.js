'use strict';
const express = require('express');
const http = require('http');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const multer = require('multer');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = Number(process.env.PORT) || 3000;
const PIN = process.env.CONTROL_PIN || '';
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const UPLOAD_DIR = path.join(DATA_DIR, 'uploads');
const STATE_FILE = path.join(DATA_DIR, 'state.json');
// Videos start this many ms in the future so every TV has time to buffer and begin together.
const START_DELAY = 800;

fs.mkdirSync(UPLOAD_DIR, { recursive: true });

let state = {
  items: [], // { id, type: 'image' | 'video', url, name }
  index: 0,
  blank: false,
  loop: false,
  // Video clock: at server time `at` (ms) the video is at `pos` seconds.
  video: { playing: false, pos: 0, at: 0 },
};
try {
  Object.assign(state, JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')));
} catch {}
state.video = { playing: false, pos: 0, at: Date.now() };

let saveTimer;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => fs.writeFile(STATE_FILE, JSON.stringify(state), () => {}), 300);
}

const currentPos = () => {
  const v = state.video;
  return Math.max(0, v.playing ? v.pos + (Date.now() - v.at) / 1000 : v.pos);
};
const num = (x, fallback) => (Number.isFinite(Number(x)) && x !== null && x !== '' ? Number(x) : fallback);

function startCurrentItem() {
  state.video = { playing: true, pos: 0, at: Date.now() + START_DELAY };
}

function handleCmd(m) {
  const n = state.items.length;
  const curId = state.items[state.index]?.id;
  switch (m.action) {
    case 'goto': {
      const i = num(m.index, -1);
      if (i >= 0 && i < n) { state.index = i; startCurrentItem(); }
      break;
    }
    case 'next':
      if (state.index < n - 1) { state.index++; startCurrentItem(); }
      break;
    case 'prev':
      if (state.index > 0) { state.index--; startCurrentItem(); }
      break;
    case 'play':
      state.video = { playing: true, pos: Math.max(0, num(m.pos, currentPos())), at: Date.now() + 300 };
      break;
    case 'pause':
      state.video = { playing: false, pos: Math.max(0, num(m.pos, currentPos())), at: Date.now() };
      break;
    case 'seek': {
      const playing = state.video.playing;
      state.video = { playing, pos: Math.max(0, num(m.pos, 0)), at: Date.now() + (playing ? 300 : 0) };
      break;
    }
    case 'blank':
      state.blank = !!m.value;
      break;
    case 'loop':
      state.loop = !!m.value;
      break;
    case 'move': {
      const from = num(m.from, -1), to = num(m.to, -1);
      if (from < 0 || from >= n || to < 0 || to >= n) return;
      const [it] = state.items.splice(from, 1);
      state.items.splice(to, 0, it);
      state.index = Math.max(0, state.items.findIndex((x) => x.id === curId));
      break;
    }
    case 'delete': {
      const i = state.items.findIndex((x) => x.id === m.id);
      if (i < 0) return;
      const [it] = state.items.splice(i, 1);
      fs.unlink(path.join(UPLOAD_DIR, path.basename(it.url)), () => {});
      if (it.id === curId) {
        state.index = Math.min(i, Math.max(0, state.items.length - 1));
        startCurrentItem();
      } else {
        state.index = Math.max(0, state.items.findIndex((x) => x.id === curId));
      }
      break;
    }
    default:
      return;
  }
  save();
  broadcastState();
}

// ---------- HTTP ----------
const app = express();
app.disable('x-powered-by');

const requirePin = (req, res, next) =>
  !PIN || req.get('x-pin') === PIN ? next() : res.status(401).json({ error: 'PIN salah' });

const upload = multer({
  storage: multer.diskStorage({
    destination: UPLOAD_DIR,
    filename: (req, f, cb) => cb(null, crypto.randomUUID() + path.extname(f.originalname).toLowerCase()),
  }),
  limits: { fileSize: 4 * 1024 ** 3 },
  fileFilter: (req, f, cb) => cb(null, /^(image|video)\//.test(f.mimetype)),
});

app.post('/api/upload', requirePin, upload.array('files', 500), (req, res) => {
  const wasEmpty = state.items.length === 0;
  for (const f of req.files || []) {
    state.items.push({
      id: path.parse(f.filename).name,
      type: f.mimetype.startsWith('video/') ? 'video' : 'image',
      url: '/media/' + f.filename,
      // multer decodes names as latin1; restore UTF-8
      name: Buffer.from(f.originalname, 'latin1').toString('utf8'),
    });
  }
  if (wasEmpty && state.items.length) { state.index = 0; startCurrentItem(); }
  save();
  broadcastState();
  res.json({ added: (req.files || []).length });
});

app.use('/media', express.static(UPLOAD_DIR, { maxAge: '30d', immutable: true }));
app.get('/tv', (req, res) => res.sendFile(path.join(__dirname, 'public', 'tv.html')));
app.get('/', (req, res) => res.sendFile(path.join(__dirname, 'public', 'control.html')));
app.use(express.static(path.join(__dirname, 'public'))); // revalidated each load, so updates reach the TVs

// ---------- WebSocket ----------
const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws' });

function send(ws, msg) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
}
function broadcastState() {
  const msg = JSON.stringify({ t: 'state', state });
  for (const ws of wss.clients) if (ws.readyState === WebSocket.OPEN && ws.role) ws.send(msg);
}
function broadcastDisplays() {
  const list = [...wss.clients].filter((c) => c.role === 'display').map((c) => c.name);
  for (const ws of wss.clients) if (ws.role === 'control') send(ws, { t: 'displays', list });
}

wss.on('connection', (ws) => {
  ws.alive = true;
  ws.on('pong', () => (ws.alive = true));
  ws.on('message', (raw) => {
    let m;
    try { m = JSON.parse(raw); } catch { return; }
    if (m.t === 'ping') return send(ws, { t: 'pong', c: m.c, s: Date.now() });
    if (m.t === 'hello') {
      ws.role = m.role === 'control' ? 'control' : 'display';
      ws.name = String(m.name || 'TV').slice(0, 40);
      if (ws.role === 'control') {
        ws.authed = !PIN || m.pin === PIN;
        send(ws, { t: 'auth', ok: ws.authed, pinRequired: !!PIN });
      }
      send(ws, { t: 'state', state });
      return broadcastDisplays();
    }
    if (m.t === 'cmd' && ws.role === 'control' && ws.authed) handleCmd(m);
  });
  ws.on('close', broadcastDisplays);
});

setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.alive) { ws.terminate(); continue; }
    ws.alive = false;
    ws.ping();
  }
}, 20000);

server.listen(PORT, () => {
  console.log(`Layar Bersama berjalan di http://localhost:${PORT}`);
  console.log(`  Kontrol : http://localhost:${PORT}/`);
  console.log(`  TV      : http://localhost:${PORT}/tv?name=TV1`);
  if (!PIN) console.log('  (CONTROL_PIN belum diset: siapa pun di jaringan bisa mengontrol)');
});
