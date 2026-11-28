'use strict';
// Only the bundled pages (file://: settings and screen picker) get the bridge; the remote control page gets nothing.
const { contextBridge, ipcRenderer } = require('electron');

if (location.protocol === 'file:') {
  contextBridge.exposeInMainWorld('layarDesktop', {
    saveServer: (url) => ipcRenderer.invoke('save-server', url),
    cancel: () => ipcRenderer.invoke('cancel-settings'),
    pickerSources: () => ipcRenderer.invoke('picker-sources'),
    pickerChoose: (id) => ipcRenderer.invoke('picker-choose', id),
  });
}
