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

  buildApk(projectName, filesMap, onProgress = null, projectRootUri = null) {
    return new Promise((resolve, reject) => {
      if (!this.isAvailable()) {
        reject(new Error('Native Android Bridge required to build APK'));
        return;
      }
      const cbId = 'cb_' + (this.nextId++);
      this.callbacks[cbId] = { resolve, reject, onProgress };
      // Resolve SAF project folder URI: explicit arg wins, else current project, else persisted.
      let rootUri = projectRootUri || null;
      try {
        if (!rootUri && window.FileSystem && window.FileSystem.currentProject && window.FileSystem.currentProject.rootUri) {
          rootUri = window.FileSystem.currentProject.rootUri;
        }
        if (!rootUri && typeof this.getPersistedProjectUri === 'function') {
          rootUri = this.getPersistedProjectUri();
        }
      } catch (e) {}
      try {
        if (rootUri) {
          window.AndroidBridge.buildApk(projectName, JSON.stringify(filesMap), cbId, rootUri);
        } else {
          window.AndroidBridge.buildApk(projectName, JSON.stringify(filesMap), cbId);
        }
      } catch (e) {
        // Fallback to 3-arg overload if 4-arg dispatch fails on old native builds.
        try {
          window.AndroidBridge.buildApk(projectName, JSON.stringify(filesMap), cbId);
        } catch (e2) {
          delete this.callbacks[cbId];
          reject(e2);
        }
      }
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
// Native toolchain check resolves via onAndroidTermuxToolchainChecked.
// Older native builds called onAndroidToolchainChecked — support both names.
function resolveToolchainCallback(callbackId, success, toolchainJson, errorMsg) {
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
}
window.onAndroidTermuxToolchainChecked = resolveToolchainCallback;
window.onAndroidToolchainChecked = resolveToolchainCallback;

// Native sends progress as a single JSON string:
//   onAndroidApkBuildProgress(callbackId, "<json:{step,totalSteps,stepName,logLine}>")
window.onAndroidApkBuildProgress = function(callbackId, progressJson, legacyTotalSteps, legacyStepName, legacyLogLine) {
  const cb = Bridge.callbacks[callbackId];
  if (cb && typeof cb.onProgress === 'function') {
    try {
      let progress = null;
      if (typeof progressJson === 'string') {
        try {
          progress = JSON.parse(progressJson);
        } catch (e) {
          progress = null;
        }
      } else if (progressJson && typeof progressJson === 'object') {
        progress = progressJson;
      }
      // Legacy 5-arg form: (callbackId, step, totalSteps, stepName, logLine)
      if (!progress && typeof progressJson === 'number') {
        progress = { step: progressJson, totalSteps: legacyTotalSteps, stepName: legacyStepName, logLine: legacyLogLine };
      }
      if (progress && typeof progress.step !== 'undefined') {
        cb.onProgress(progress);
      }
    } catch (e) {
      console.warn('Failed to parse APK build progress', e);
    }
  }
};

// Native sends completion as (callbackId, successBool, "<json:{apkPath,apkName,apkSize,...}>", errorMsg).
// A legacy native form sent (callbackId, success, apkPath, apkName, apkSize, errorMsg) — support both.
window.onAndroidApkBuildComplete = function(callbackId, success, resultJson, errorMsg, legacyApkName, legacyApkSize) {
  const cb = Bridge.callbacks[callbackId];
  if (!cb) return;
  delete Bridge.callbacks[callbackId];
  if (success) {
    try {
      let result = null;
      if (typeof resultJson === 'string') {
        try {
          result = JSON.parse(resultJson);
        } catch (e) {
          result = null;
        }
      } else if (resultJson && typeof resultJson === 'object') {
        result = resultJson;
      }
      // Legacy 6-arg form fallback.
      if (!result && typeof resultJson === 'string' && legacyApkName) {
        result = { success: true, apkPath: resultJson, apkName: legacyApkName, apkSize: legacyApkSize };
      }
      if (!result || !result.apkPath) {
        cb.reject(new Error('APK build returned an empty result (null path). Please retry the build.'));
        return;
      }
      // Normalize nulls so UI never renders "null".
      result.success = true;
      result.apkName = result.apkName || (result.apkPath ? result.apkPath.split('/').pop() : 'app-debug.apk');
      result.apkSize = Number(result.apkSize) || 0;
      cb.resolve(result);
    } catch (e) {
      cb.reject(e);
    }
  } else {
    const msg = (typeof resultJson === 'string' && resultJson) ? resultJson : (errorMsg || 'APK compilation failed');
    cb.reject(new Error(msg));
  }
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
