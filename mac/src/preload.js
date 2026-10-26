// Bridge between the toolbar UI and the main process. Pages in tabs never get this.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('veil', {
  call: (channel, ...args) => ipcRenderer.invoke(channel, ...args),
  onState: (fn) => ipcRenderer.on('state', (_e, s) => fn(s)),
  onFocusUrl: (fn) => ipcRenderer.on('focus-url', fn),
});
