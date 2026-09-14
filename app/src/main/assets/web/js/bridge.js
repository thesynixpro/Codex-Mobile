// Codex Mobile - Android Native Bridge Manager
const Bridge = {
  callbacks: {},
  nextId: 1,

  isAvailable() {
    return !!(window.AndroidBridge && typeof window.AndroidBridge.isNativeAndroid === 'function');
  },

  getDeviceInfo() {
    if (!this.isAvailable()) {
      return { os: 'Browser', termuxSupported: false, device: navigator.userAgent };
    }
    try {
      return JSON.parse(window.AndroidBridge.getDeviceInfo());
    } catch (e) {
      return { os: 'Android', termuxSupported: true };
    }
  },

  getPersistedProjectUri() {
    if (!this.isAvailable()) return null;
    try {
      const uri = window.AndroidBridge.getPersistedProjectUri();
      return uri && uri.length > 0 ? uri : null;
    } catch (e) {
      return null;
    }
  },

  clearPersistedProject() {
    if (this.isAvailable()) {
      try {
        window.AndroidBridge.clearPersistedProject();
      } catch (e) {}
    }
  },

  requestDirectory() {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        reject(new Error('Android native bridge is not available.'));
        return;
      }
      this.pendingDirResolve = resolve;
      this.pendingDirReject = reject;
      window.AndroidBridge.requestProjectDirectory();
    });
  },

  rescanProject(rootUri) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        reject(new Error('Native bridge unavailable'));
        return;
      }
      const cbId = 'rescan_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.rescanProject(rootUri, cbId);
    });
  },

  writeRelativeFile(rootUri, relativePath, content) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        reject(new Error('Native bridge unavailable'));
        return;
      }
      const cbId = 'write_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.writeRelativeFile(rootUri, relativePath, content, cbId);
    });
  },

  deleteRelativeFile(rootUri, relativePath) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        reject(new Error('Native bridge unavailable'));
        return;
      }
      const cbId = 'del_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.deleteRelativeFile(rootUri, relativePath, cbId);
    });
  },

  readRelativeFile(rootUri, relativePath) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        reject(new Error('Native bridge unavailable'));
        return;
      }
      const cbId = 'read_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.readRelativeFile(rootUri, relativePath, cbId);
    });
  },

  applyBatchChanges(rootUri, changes) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        reject(new Error('Native bridge unavailable'));
        return;
      }
      const cbId = 'batch_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      const changesJson = JSON.stringify(changes);
      window.AndroidBridge.applyBatchChanges(rootUri, changesJson, cbId);
    });
  },

  readFile(uri) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        reject(new Error('Native bridge unavailable'));
        return;
      }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.readFile(uri, cbId);
    });
  },

  writeFile(uri, content) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        reject(new Error('Native bridge unavailable'));
        return;
      }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.writeFile(uri, content, cbId);
    });
  },

  createFile(parentUri, name, mimeType = 'text/plain') {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        reject(new Error('Native bridge unavailable'));
        return;
      }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.createFile(parentUri, name, mimeType, cbId);
    });
  },

  deleteFile(uri) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        reject(new Error('Native bridge unavailable'));
        return;
      }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.deleteFile(uri, cbId);
    });
  },

  // --- Local Room DB Chat Methods ---
  getChatConversations() {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) { resolve([]); return; }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.getChatConversations(cbId);
    });
  },

  searchChatConversations(query) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) { resolve([]); return; }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.searchChatConversations(query, cbId);
    });
  },

  getChatMessages(conversationId) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) { resolve([]); return; }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.getChatMessages(conversationId, cbId);
    });
  },

  createChatConversation(title, projectUri, projectName, model) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        resolve({ id: 'web_' + Date.now(), title, projectUri, projectName, model, createdAt: Date.now(), updatedAt: Date.now() });
        return;
      }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.createChatConversation(title, projectUri || '', projectName || '', model || '', cbId);
    });
  },

  saveChatMessage(conversationId, role, content, proposalsJson = '', appliedStatus = false) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) { resolve(true); return; }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.saveChatMessage(conversationId, role, content, proposalsJson || '', appliedStatus, cbId);
    });
  },

  updateChatTitle(conversationId, newTitle) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) { resolve(true); return; }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.updateChatTitle(conversationId, newTitle, cbId);
    });
  },

  deleteChatConversation(conversationId) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) { resolve(true); return; }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.deleteChatConversation(conversationId, cbId);
    });
  },

  // --- Background AI Task Methods ---
  startBackgroundAiTask(params) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) { reject(new Error('Native bridge unavailable')); return; }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.startBackgroundAiTask(JSON.stringify(params), cbId);
    });
  },

  getBackgroundTasks() {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) { resolve([]); return; }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.getBackgroundTasks(cbId);
    });
  },

  dismissBackgroundTask(taskId) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) { resolve(true); return; }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.dismissBackgroundTask(taskId, cbId);
    });
  },

  // --- Android APK Build & Termux Methods ---
  checkTermuxToolchain() {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        resolve({
          termuxInstalled: false,
          openJdkAvailable: false,
          gradleAvailable: false,
          buildToolsAvailable: false,
          setupScript: "pkg update -y && pkg install -y openjdk-17 aapt2 d8 apksigner"
        });
        return;
      }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.checkTermuxToolchain(cbId);
    });
  },

  buildApk(projectName, filesMap, onProgress = null) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        reject(new Error('Native Android Bridge required to build APK'));
        return;
      }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject, onProgress };
      window.AndroidBridge.buildApk(projectName, JSON.stringify(filesMap), cbId);
    });
  },

  shareApk(apkPath) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) { reject(new Error('Native bridge unavailable')); return; }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.shareApk(apkPath, cbId);
    });
  },

  installApk(apkPath) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) { reject(new Error('Native bridge unavailable')); return; }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      window.AndroidBridge.installApk(apkPath, cbId);
    });
  },

  saveApkToDownloads(apkPath) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) { reject(new Error('Native bridge unavailable')); return; }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject };
      if (typeof window.AndroidBridge.saveApkToDownloads === 'function') {
        window.AndroidBridge.saveApkToDownloads(apkPath, cbId);
      } else {
        resolve(apkPath);
      }
    });
  },

  log(msg) {
    if (this.isAvailable()) {
      try {
        window.AndroidBridge.logToNative(msg);
      } catch (e) {}
    }
  }
};

// Global hooks called by AndroidBridge
window.onAndroidDirectoryStarted = function(rootName, rootUri) {
  if (window.App && typeof window.App.dismissProjectPrompt === 'function') {
    window.App.dismissProjectPrompt();
  }
  if (window.FileSystem && typeof window.FileSystem.onDirectoryPickingStarted === 'function') {
    window.FileSystem.onDirectoryPickingStarted(rootName, rootUri);
  }
};

window.onAndroidDirectorySelected = function(jsonTree, rootName, rootUri) {
  try {
    if (window.App && typeof window.App.dismissProjectPrompt === 'function') {
      window.App.dismissProjectPrompt();
    }
    const tree = typeof jsonTree === 'string' ? JSON.parse(jsonTree) : jsonTree;
    if (Bridge.pendingDirResolve) {
      Bridge.pendingDirResolve({ tree, rootName, rootUri });
      Bridge.pendingDirResolve = null;
      Bridge.pendingDirReject = null;
    }
    if (window.FileSystem) {
      window.FileSystem.loadAndroidDirectory(tree, rootName, rootUri);
    }
  } catch (e) {
    if (Bridge.pendingDirReject) {
      Bridge.pendingDirReject(e);
      Bridge.pendingDirResolve = null;
      Bridge.pendingDirReject = null;
    }
  }
};

window.onAndroidDirectoryAutoLoaded = function(jsonTree, rootName, rootUri) {
  try {
    if (window.App && typeof window.App.dismissProjectPrompt === 'function') {
      window.App.dismissProjectPrompt();
    }
    const tree = typeof jsonTree === 'string' ? JSON.parse(jsonTree) : jsonTree;
    if (window.FileSystem) {
      window.FileSystem.loadAndroidDirectory(tree, rootName, rootUri, true);
    }
  } catch (e) {
    console.error("Failed to auto-load directory", e);
  }
};

window.onAndroidDirectoryCancelled = function() {
  if (Bridge.pendingDirReject) {
    Bridge.pendingDirReject(new Error("Directory selection cancelled"));
    Bridge.pendingDirResolve = null;
    Bridge.pendingDirReject = null;
  }
  if (window.FileSystem && typeof window.FileSystem.onDirectoryPickingCancelled === 'function') {
    window.FileSystem.onDirectoryPickingCancelled();
  }
};

window.onAndroidDirectoryError = function(errorMsg) {
  if (Bridge.pendingDirReject) {
    Bridge.pendingDirReject(new Error(errorMsg));
    Bridge.pendingDirResolve = null;
    Bridge.pendingDirReject = null;
  }
  if (window.FileSystem && typeof window.FileSystem.onDirectoryPickingError === 'function') {
    window.FileSystem.onDirectoryPickingError(errorMsg);
  }
  if (window.Terminal) {
    window.Terminal.log(`Android SAF error: ${errorMsg}`, "error");
  }
  if (window.App && window.App.showToast) {
    window.App.showToast(`Directory error: ${errorMsg}`);
  }
};

window.onAndroidProjectRescanned = function(callbackId, success, treeJson, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) {
    try {
      const tree = typeof treeJson === 'string' ? JSON.parse(treeJson) : treeJson;
      cb.resolve(tree);
    } catch (e) {
      cb.reject(e);
    }
  } else {
    cb.reject(new Error(errorMsg || 'Failed to rescan project'));
  }
};

window.onAndroidBatchApplied = function(callbackId, success, resultJson, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) {
    try {
      const res = typeof resultJson === 'string' ? JSON.parse(resultJson) : resultJson;
      cb.resolve(res);
    } catch (e) {
      cb.reject(e);
    }
  } else {
    cb.reject(new Error(errorMsg || 'Failed to apply batch changes'));
  }
};

window.onAndroidFileRead = function(callbackId, success, content, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) cb.resolve(content);
  else cb.reject(new Error(errorMsg || 'Read failed'));
};

window.onAndroidFileWritten = function(callbackId, success, uri, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) cb.resolve(uri || true);
  else cb.reject(new Error(errorMsg || 'Write failed'));
};

window.onAndroidFileCreated = function(callbackId, success, createdUri, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) cb.resolve(createdUri);
  else cb.reject(new Error(errorMsg || 'Create failed'));
};

window.onAndroidFileDeleted = function(callbackId, success, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) cb.resolve(true);
  else cb.reject(new Error(errorMsg || 'Delete failed'));
};

// --- Chat DB Global Callbacks ---
window.onAndroidChatConversationsLoaded = function(callbackId, success, conversationsJson, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) {
    try {
      const convos = typeof conversationsJson === 'string' ? JSON.parse(conversationsJson) : conversationsJson;
      cb.resolve(convos || []);
    } catch (e) {
      cb.resolve([]);
    }
  } else {
    cb.reject(new Error(errorMsg || 'Failed to load conversations'));
  }
};

window.onAndroidChatMessagesLoaded = function(callbackId, success, messagesJson, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) {
    try {
      const msgs = typeof messagesJson === 'string' ? JSON.parse(messagesJson) : messagesJson;
      cb.resolve(msgs || []);
    } catch (e) {
      cb.resolve([]);
    }
  } else {
    cb.reject(new Error(errorMsg || 'Failed to load messages'));
  }
};

window.onAndroidChatConversationCreated = function(callbackId, success, conversationJson, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) {
    try {
      const convo = typeof conversationJson === 'string' ? JSON.parse(conversationJson) : conversationJson;
      cb.resolve(convo);
    } catch (e) {
      cb.reject(e);
    }
  } else {
    cb.reject(new Error(errorMsg || 'Failed to create conversation'));
  }
};

window.onAndroidChatMessageSaved = function(callbackId, success, messageJson, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) {
    try {
      const msg = typeof messageJson === 'string' ? JSON.parse(messageJson) : messageJson;
      cb.resolve(msg);
    } catch (e) {
      cb.resolve(true);
    }
  } else {
    cb.reject(new Error(errorMsg || 'Failed to save message'));
  }
};

window.onAndroidChatTitleUpdated = function(callbackId, success, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) cb.resolve(true);
  else cb.reject(new Error(errorMsg || 'Failed to update title'));
};

window.onAndroidChatConversationDeleted = function(callbackId, success, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) cb.resolve(true);
  else cb.reject(new Error(errorMsg || 'Failed to delete conversation'));
};

// --- Background Task Global Callbacks ---
window.onAndroidBackgroundTaskStarted = function(callbackId, success, taskJson, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) {
    try {
      const task = typeof taskJson === 'string' ? JSON.parse(taskJson) : taskJson;
      cb.resolve(task);
    } catch (e) {
      cb.reject(e);
    }
  } else {
    cb.reject(new Error(errorMsg || 'Failed to start background task'));
  }
};

window.onAndroidBackgroundTasksLoaded = function(callbackId, success, tasksJson, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) {
    try {
      const tasks = typeof tasksJson === 'string' ? JSON.parse(tasksJson) : tasksJson;
      cb.resolve(tasks || []);
    } catch (e) {
      cb.resolve([]);
    }
  } else {
    cb.reject(new Error(errorMsg || 'Failed to load background tasks'));
  }
};

window.onAndroidBackgroundTaskDismissed = function(callbackId, success, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) cb.resolve(true);
  else cb.reject(new Error(errorMsg || 'Failed to dismiss task'));
};

// --- APK Build Global Callbacks ---
window.onAndroidTermuxToolchainChecked = window.onAndroidToolchainChecked = function(callbackId, success, toolchainJson, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) {
    try {
      const info = typeof toolchainJson === 'string' ? JSON.parse(toolchainJson) : toolchainJson;
      cb.resolve(info);
    } catch (e) {
      cb.reject(e);
    }
  } else {
    cb.reject(new Error(errorMsg || 'Failed to check toolchain'));
  }
};

window.onAndroidApkBuildProgress = function(callbackId, step, totalSteps, stepName, logLine) {
  const cb = Bridge.callbacks[callbackId];
  if (cb && typeof cb.onProgress === 'function') {
    if (typeof step === 'object' && step !== null) {
      cb.onProgress(step);
      return;
    }
    if (typeof step === 'string' && step.trim().startsWith('{')) {
      try {
        const parsed = JSON.parse(step);
        cb.onProgress(parsed);
        return;
      } catch (e) {}
    }
    cb.onProgress({ step, totalSteps, stepName, logLine });
  }
};

window.onAndroidApkBuildComplete = function(callbackId, success, apkPath, apkName, apkSize, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) {
    let resolvedPath = apkPath;
    let resolvedName = apkName;
    let resolvedSize = apkSize;

    let resolvedProjectPath = '';
    let resolvedDownloadsPath = '';

    if (typeof apkPath === 'string' && apkPath.trim().startsWith('{')) {
      try {
        const parsed = JSON.parse(apkPath);
        resolvedPath = parsed.apkPath || parsed.path || apkPath;
        resolvedName = parsed.apkName || parsed.name || (resolvedPath ? resolvedPath.split('/').pop() : 'WebApp-debug.apk');
        resolvedSize = parsed.apkSize || parsed.size || 0;
        resolvedProjectPath = parsed.projectPath || '';
        resolvedDownloadsPath = parsed.downloadsPath || '';
      } catch (e) {}
    } else if (typeof apkPath === 'object' && apkPath !== null) {
      resolvedPath = apkPath.apkPath || apkPath.path || '';
      resolvedName = apkPath.apkName || apkPath.name || (resolvedPath ? resolvedPath.split('/').pop() : 'WebApp-debug.apk');
      resolvedSize = apkPath.apkSize || apkPath.size || 0;
      resolvedProjectPath = apkPath.projectPath || '';
      resolvedDownloadsPath = apkPath.downloadsPath || '';
    }

    if (!resolvedName && resolvedPath && typeof resolvedPath === 'string') {
      resolvedName = resolvedPath.split('/').pop() || 'WebApp-debug.apk';
    }
    if (!resolvedName || resolvedName === 'null') {
      resolvedName = 'WebApp-debug.apk';
    }
    if (typeof resolvedSize !== 'number' || isNaN(resolvedSize) || resolvedSize <= 0) {
      resolvedSize = 1024 * 1450;
    }

    cb.resolve({
      success: true,
      apkPath: resolvedPath,
      apkName: resolvedName,
      apkSize: resolvedSize,
      projectPath: resolvedProjectPath,
      downloadsPath: resolvedDownloadsPath
    });
  } else {
    const err = errorMsg || (typeof apkName === 'string' ? apkName : (typeof apkPath === 'string' && !apkPath.startsWith('{') ? apkPath : 'APK compilation failed'));
    cb.reject(new Error(err));
  }
};

window.onAndroidApkSavedToDownloads = function(callbackId, success, destPath, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) cb.resolve(destPath || true);
  else cb.reject(new Error(errorMsg || 'Failed to save APK to Downloads'));
};

window.onAndroidApkShared = function(callbackId, success, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) cb.resolve(true);
  else cb.reject(new Error(errorMsg || 'Failed to share APK'));
};

window.onAndroidApkInstalled = function(callbackId, success, errorMsg) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) cb.resolve(true);
  else cb.reject(new Error(errorMsg || 'Failed to launch installer'));
};

window.Bridge = Bridge;
