'use strict';
// Only the bundled settings page (file://) gets the bridge; the remote control page gets nothing.
const { contextBridge, ipcRenderer } = require('electron');

if (location.protocol === 'file:') {
  contextBridge.exposeInMainWorld('layarDesktop', {
    saveServer: (url) => ipcRenderer.invoke('save-server', url),
    cancel: () => ipcRenderer.invoke('cancel-settings'),
  });
}
