'use strict';
// Layar Bersama controller for Windows: opens the server's control page in its own window.
const { app, BrowserWindow, Menu, ipcMain, shell, dialog, clipboard } = require('electron');
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
  const { server } = readConfig();
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
  app.whenReady().then(createWindow);
  app.on('window-all-closed', () => app.quit());
}
