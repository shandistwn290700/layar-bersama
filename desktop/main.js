'use strict';
// Layar Bersama controller for Windows: opens the server's control page in its own window.
const { app, BrowserWindow, Menu, ipcMain, shell, dialog, clipboard, session, desktopCapturer } = require('electron');
const path = require('path');
const fs = require('fs');

const CONFIG_FILE = path.join(app.getPath('userData'), 'config.json');
let win;

function readConfig() {
  try { return JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')); } catch { return {}; }
}
function writeConfig(cfg) {
  fs.mkdirSync(path.dirname(CONFIG_FILE), { recursive: true });
  fs.writeFileSync(CONFIG_FILE, JSON.stringify(cfg, null, 2));
}

/** Adds a scheme when missing: http for plain IP addresses (local network), https otherwise. */
function normalizeUrl(raw) {
  let s = String(raw || '').trim();
  if (!s || /^https?:\/\/$/i.test(s)) return null;
  if (!/^https?:\/\//i.test(s)) {
    const ip = /^\d{1,3}(\.\d{1,3}){3}(:\d+)?(\/.*)?$/.test(s);
    s = (ip ? 'http://' : 'https://') + s;
  }
  try { return new URL(s).origin; } catch { return null; }
}

function showSettings(error) {
  const query = { server: readConfig().server || '' };
  if (error) query.error = error;
  win.loadFile(path.join(__dirname, 'settings.html'), { query });
}

function showControl() {
  const server = process.env.LAYAR_SERVER || readConfig().server; // env var is for testing against a local server
  if (!server) return showSettings();
  win.loadURL(server + '/');
}

function buildMenu() {
  const template = [
    {
      label: 'Layar Bersama',
      submenu: [
        { label: 'Ganti alamat server…', accelerator: 'CmdOrCtrl+,', click: () => showSettings() },
        {
          label: 'Salin alamat untuk TV',
          click: () => {
            const { server } = readConfig();
            if (!server) return showSettings();
            const urls = [server + '/tv?name=TV1', server + '/tv?name=TV2'];
            clipboard.writeText(urls.join('\n'));
            dialog.showMessageBox(win, {
              type: 'info', title: 'Alamat untuk TV',
              message: 'Alamat halaman TV sudah disalin:',
              detail: urls.join('\n') + '\n\nDi app Google TV, cukup isi alamat server: ' + server,
            });
          },
        },
        { label: 'Buka di browser', click: () => { const { server } = readConfig(); if (server) shell.openExternal(server + '/'); } },
        { type: 'separator' },
        { label: 'Keluar', role: 'quit' },
      ],
    },
    {
      label: 'Tampilan',
      submenu: [
        { label: 'Muat ulang', accelerator: 'CmdOrCtrl+R', click: () => showControl() },
        { label: 'Layar penuh', role: 'togglefullscreen' },
        { type: 'separator' },
        { label: 'Perbesar', role: 'zoomIn' },
        { label: 'Perkecil', role: 'zoomOut' },
        { label: 'Ukuran normal', role: 'resetZoom' },
      ],
    },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 420,
    minHeight: 500,
    title: 'Layar Bersama: Kontrol',
    icon: path.join(__dirname, 'build', 'icon.png'),
    backgroundColor: '#151514',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  // Links that would open a new window go to the normal browser instead.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });

  win.webContents.on('did-fail-load', (e, code, desc, url, isMainFrame) => {
    if (!isMainFrame || code === -3) return; // -3 = navigation aborted (e.g. a reload)
    showSettings(`Tidak bisa membuka ${url} (${desc}). Periksa alamat server atau koneksi jaringan.`);
  });

  buildMenu();
  showControl();
}

// ---------- Screen picker for "Cast layar" ----------
// Electron has no built-in picker for getDisplayMedia, so the app shows its own window of screens and windows.
let pickerResolve = null;
let pickerSources = [];

function pickSource() {
  return new Promise(async (resolve) => {
    const sources = await desktopCapturer.getSources({ types: ['screen', 'window'], thumbnailSize: { width: 320, height: 180 } });
    pickerSources = sources.filter((s) => !s.name.startsWith('Layar Bersama: Pilih'));
    const picker = new BrowserWindow({
      parent: win, modal: true, width: 760, height: 560, minimizable: false, maximizable: false,
      title: 'Layar Bersama: Pilih yang akan di-cast', backgroundColor: '#151514', autoHideMenuBar: true,
      webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true },
    });
    pickerResolve = (id) => {
      pickerResolve = null;
      resolve(pickerSources.find((s) => s.id === id) || null);
      if (!picker.isDestroyed()) picker.close();
    };
    picker.on('closed', () => pickerResolve && pickerResolve(null));
    picker.loadFile(path.join(__dirname, 'picker.html'));
  });
}

ipcMain.handle('picker-sources', () =>
  pickerSources.map((s) => ({ id: s.id, name: s.name, screen: s.id.startsWith('screen:'), thumb: s.thumbnail.toDataURL() })));
ipcMain.handle('picker-choose', (e, id) => { if (pickerResolve) pickerResolve(id); });

function setupScreenCapture() {
  session.defaultSession.setDisplayMediaRequestHandler(async (request, callback) => {
    const source = await pickSource();
    if (!source) return callback({}); // cancelled: the page gets NotAllowedError
    // Whole screens also send the computer's sound; single windows send picture only.
    callback(source.id.startsWith('screen:') ? { video: source, audio: 'loopback' } : { video: source });
  });
}

ipcMain.handle('save-server', (e, raw) => {
  const server = normalizeUrl(raw);
  if (!server) return { ok: false, error: 'Alamat server tidak valid. Contoh: https://layar.contoh.com' };
  writeConfig({ ...readConfig(), server });
  showControl();
  return { ok: true };
});
ipcMain.handle('cancel-settings', () => { if (readConfig().server) showControl(); });

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
  app.whenReady().then(() => { setupScreenCapture(); createWindow(); });
  app.on('window-all-closed', () => app.quit());
}
