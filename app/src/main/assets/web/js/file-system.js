// Codex Mobile - Project-Folder Native Filesystem Manager
const FileSystem = {
  currentProject: null, // { name, type: 'native' | 'fsa' | 'memory', rootUri, handle }
  files: {}, // path -> { name, path, content, isDir, parent, uri, handle, isDirty }
  activeFilePath: null,
  listeners: [],
  isScanning: false,

  init() {
    // If running in native Android, check if a valid persisted project URI is stored
    if (window.Bridge && window.Bridge.isAvailable()) {
      const persistedUri = window.Bridge.getPersistedProjectUri();
      if (persistedUri) {
        if (window.Terminal) {
          window.Terminal.log(`Found persisted Android project URI, restoring...`, "system");
        }
        // Rescan will be called or auto-loaded via MainActivity.checkAndNotifyPersistedProject()
        this.rescanNativeProject(persistedUri).catch(err => {
          console.warn("Could not restore persisted project:", err);
          this.currentProject = null;
          this.notify();
        });
        return;
      }
    }

    // Otherwise, start in "No Project Selected" state
    this.currentProject = null;
    this.files = {};
    this.activeFilePath = null;
    this.notify();
  },

  hasProject() {
    return !!(this.currentProject && this.currentProject.name);
  },

  subscribe(listener) {
    this.listeners.push(listener);
  },

  notify() {
    for (const listener of this.listeners) {
      listener(this);
    }
  },

  // Open directory dispatcher (Native SAF -> Browser FSA)
  async openProjectDirectory() {
    if (window.Bridge && window.Bridge.isAvailable()) {
      if (window.Terminal) {
        window.Terminal.log("Launching Android system folder picker...", "system");
      }
      try {
        await window.Bridge.requestDirectory();
      } catch (err) {
        if (window.Terminal) window.Terminal.log(`Folder picker error: ${err.message}`, "error");
        if (window.App && window.App.showToast) window.App.showToast(`Error: ${err.message}`);
      }
      return;
    }

    if (window.showDirectoryPicker) {
      try {
        const dirHandle = await window.showDirectoryPicker({ mode: 'readwrite' });
        await this.loadDirectoryHandle(dirHandle);
      } catch (err) {
        if (err.name !== 'AbortError') {
          if (window.Terminal) window.Terminal.log(`Directory picker error: ${err.message}`, "error");
          alert(`Directory picker error: ${err.message}`);
        }
      }
      return;
    }

    // Fallback for browsers without File System Access API
    alert("Folder selection requires Android APK or a browser supporting the File System Access API (such as Chrome or Edge).");
  },

  changeProjectDirectory() {
    return this.openProjectDirectory();
  },

  onDirectoryPickingStarted(rootName, rootUri) {
    this.isScanning = true;
    if (!this.currentProject) {
      this.currentProject = {
        name: rootName || "Project",
        type: 'native',
        rootUri: rootUri,
        handle: null
      };
    }
    // Instantly dismiss overlay so it never stays stuck
    if (window.App && typeof window.App.dismissProjectPrompt === 'function') {
      window.App.dismissProjectPrompt();
    }
    this.notify();
    if (window.Terminal) {
      window.Terminal.log(`Selected project directory: "${rootName || 'Project'}". Scanning directory tree...`, "system");
    }
  },

  onDirectoryPickingCancelled() {
    this.isScanning = false;
    if (!this.hasProject() && window.App && typeof window.App.showProjectPrompt === 'function') {
      window.App.showProjectPrompt();
    }
    this.notify();
    if (window.Terminal) {
      window.Terminal.log("Directory selection cancelled by user.", "info");
    }
  },

  onDirectoryPickingError(errorMsg) {
    this.isScanning = false;
    if (!this.hasProject() && window.App && typeof window.App.showProjectPrompt === 'function') {
      window.App.showProjectPrompt();
    }
    this.notify();
  },

  // Android Native SAF Directory loader
  loadAndroidDirectory(tree, rootName, rootUri, isAutoRestore = false) {
    this.isScanning = false;
    this.currentProject = {
      name: rootName || "Project",
      type: 'native',
      rootUri: rootUri,
      handle: null
    };
    this.files = {};

    // Ensure prompt overlay is dismissed
    if (window.App && typeof window.App.dismissProjectPrompt === 'function') {
      window.App.dismissProjectPrompt();
    }

    const processChildren = (children, parentPath = "") => {
      if (!children || !Array.isArray(children)) return;
      for (const item of children) {
        const itemPath = item.path || (parentPath ? `${parentPath}/${item.name}` : item.name);
        this.files[itemPath] = {
          name: item.name,
          path: itemPath,
          content: null, // lazy-loaded on open
          isDir: item.isDirectory,
          uri: item.uri,
          isDirty: false
        };
        if (item.isDirectory && item.children) {
          processChildren(item.children, itemPath);
        }
      }
    };

    if (tree && tree.children) {
      processChildren(tree.children);
    }

    // Select primary code file if present
    const firstCodeFile = Object.keys(this.files).find(p => !this.files[p].isDir);
    if (firstCodeFile) {
      this.openFile(firstCodeFile);
    } else {
      this.activeFilePath = null;
      if (window.Editor) {
        window.Editor.clear();
      }
    }

    this.notify();

    if (window.Terminal) {
      const fileCount = Object.keys(this.files).filter(p => !this.files[p].isDir).length;
      window.Terminal.log(
        `${isAutoRestore ? 'Restored' : 'Loaded'} Android project folder: "${this.currentProject.name}" (${fileCount} files)`,
        "fs"
      );
    }

    if (window.App && window.App.showToast) {
      window.App.showToast(`Opened project folder: ${this.currentProject.name}`);
    }
  },

  // Refresh active project folder
  async refreshProject() {
    if (!this.currentProject) return;

    this.isScanning = true;
    this.notify();

    if (window.Terminal) {
      window.Terminal.log(`Refreshing project folder: "${this.currentProject.name}"...`, "system");
    }

    try {
      if (this.currentProject.type === 'native' && this.currentProject.rootUri) {
        await this.rescanNativeProject(this.currentProject.rootUri);
      } else if (this.currentProject.type === 'fsa' && this.currentProject.handle) {
        await this.loadDirectoryHandle(this.currentProject.handle);
      }
      if (window.Terminal) {
        window.Terminal.log(`Project folder "${this.currentProject.name}" refreshed successfully.`, "fs");
      }
      if (window.App && window.App.showToast) {
        window.App.showToast(`Refreshed ${this.currentProject.name}`);
      }
    } catch (err) {
      if (window.Terminal) {
        window.Terminal.log(`Failed to refresh project: ${err.message}`, "error");
      }
      if (window.App && window.App.showToast) {
        window.App.showToast(`Refresh error: ${err.message}`);
      }
    } finally {
      this.isScanning = false;
      this.notify();
    }
  },

  async rescanNativeProject(rootUri) {
    if (!window.Bridge || !window.Bridge.isAvailable()) return;
    const tree = await window.Bridge.rescanProject(rootUri);
    const rootName = tree.name || (this.currentProject ? this.currentProject.name : "Project");
    this.loadAndroidDirectory(tree, rootName, rootUri, true);
  },

  // Browser File System Access API loader
  async loadDirectoryHandle(dirHandle) {
    this.currentProject = {
      name: dirHandle.name,
      type: 'fsa',
      rootUri: null,
      handle: dirHandle
    };
    this.files = {};

    const readEntries = async (handle, parentPath = "") => {
      for await (const [name, entry] of handle.entries()) {
        if (name.startsWith('.') || name === 'node_modules') continue;
        const path = parentPath ? `${parentPath}/${name}` : name;
        if (entry.kind === 'directory') {
          this.files[path] = {
            name,
            path,
            content: null,
            isDir: true,
            handle: entry,
            isDirty: false
          };
          await readEntries(entry, path);
        } else {
          this.files[path] = {
            name,
            path,
            content: null,
            isDir: false,
            handle: entry,
            isDirty: false
          };
        }
      }
    };

    await readEntries(dirHandle);

    const firstCodeFile = Object.keys(this.files).find(p => !this.files[p].isDir);
    if (firstCodeFile) {
      await this.openFile(firstCodeFile);
    } else {
      this.activeFilePath = null;
      if (window.Editor) window.Editor.clear();
    }

    this.notify();
    if (window.Terminal) {
      window.Terminal.log(`Mounted directory via FileSystemAccess: ${dirHandle.name}`, "fs");
    }
  },

  async openFile(path) {
    const file = this.files[path];
    if (!file || file.isDir) return;

    if (path.toLowerCase().endsWith('.apk')) {
      if (window.ApkBuilder && typeof window.ApkBuilder.showApkFileActions === 'function') {
        window.ApkBuilder.showApkFileActions(path);
        return;
      }
    }

    if (file.content === null) {
      try {
        if (this.currentProject && this.currentProject.type === 'native' && this.currentProject.rootUri) {
          file.content = await window.Bridge.readRelativeFile(this.currentProject.rootUri, path);
        } else if (this.currentProject && this.currentProject.type === 'fsa' && file.handle) {
          const fileObj = await file.handle.getFile();
          file.content = await fileObj.text();
        } else {
          file.content = "";
        }
      } catch (err) {
        if (window.Terminal) window.Terminal.log(`Error reading ${path}: ${err.message}`, "error");
        file.content = `/* Error reading file: ${err.message} */`;
      }
    }

    this.activeFilePath = path;
    if (window.Editor) {
      window.Editor.loadFile(path, file.content);
    }
    this.notify();
  },

  async saveFile(path, content) {
    if (!this.hasProject()) {
      alert("No project folder selected. Please choose a Project Directory first.");
      return false;
    }

    let file = this.files[path];
    if (!file) {
      // Create it if it doesn't exist yet
      return await this.createFile(path, content);
    }

    file.content = content;
    file.isDirty = false;

    if (this.currentProject.type === 'native' && this.currentProject.rootUri) {
      try {
        await window.Bridge.writeRelativeFile(this.currentProject.rootUri, path, content);
        if (window.Terminal) window.Terminal.log(`Saved ${path} directly to project folder.`, "fs");
        this.notify();
        return true;
      } catch (err) {
        if (window.Terminal) window.Terminal.log(`Failed to save ${path}: ${err.message}`, "error");
        alert(`Failed to save to device: ${err.message}`);
        return false;
      }
    } else if (this.currentProject.type === 'fsa') {
      try {
        await this.writeFsaFile(path, content);
        if (window.Terminal) window.Terminal.log(`Saved ${path} via FileSystemAccess.`, "fs");
        this.notify();
        return true;
      } catch (err) {
        if (window.Terminal) window.Terminal.log(`Failed to write ${path}: ${err.message}`, "error");
        alert(`Failed to write file: ${err.message}`);
        return false;
      }
    } else {
      if (window.Terminal) window.Terminal.log(`Saved ${path} locally.`, "fs");
      this.notify();
      return true;
    }
  },

  async createFile(path, content = "") {
    if (!this.hasProject()) {
      alert("Please select a Project Directory before creating files.");
      return false;
    }

    const cleanPath = path.trim().replace(/^[\/\\]+/, '');
    if (this.files[cleanPath]) {
      alert(`File "${cleanPath}" already exists in project folder.`);
      return false;
    }

    const name = cleanPath.split('/').pop();

    if (this.currentProject.type === 'native' && this.currentProject.rootUri) {
      try {
        await window.Bridge.writeRelativeFile(this.currentProject.rootUri, cleanPath, content);
        await this.refreshProject();
      } catch (err) {
        if (window.Terminal) window.Terminal.log(`Failed to create file: ${err.message}`, "error");
        alert(`Failed to create file: ${err.message}`);
        return false;
      }
    } else if (this.currentProject.type === 'fsa' && this.currentProject.handle) {
      try {
        await this.writeFsaFile(cleanPath, content);
        await this.refreshProject();
      } catch (err) {
        if (window.Terminal) window.Terminal.log(`Failed to create file: ${err.message}`, "error");
        alert(`Failed to create file: ${err.message}`);
        return false;
      }
    } else {
      this.files[cleanPath] = {
        name,
        path: cleanPath,
        content,
        isDir: false,
        isDirty: false
      };
    }

    await this.openFile(cleanPath);
    if (window.Terminal) window.Terminal.log(`Created file: ${cleanPath}`, "fs");
    this.notify();
    return true;
  },

  async deleteFile(path) {
    if (!this.hasProject()) return false;
    const file = this.files[path];
    if (!file) return false;

    if (!confirm(`Are you sure you want to delete "${path}" from "${this.currentProject.name}"?`)) {
      return false;
    }

    if (this.currentProject.type === 'native' && this.currentProject.rootUri) {
      try {
        await window.Bridge.deleteRelativeFile(this.currentProject.rootUri, path);
      } catch (err) {
        if (window.Terminal) window.Terminal.log(`Native delete error: ${err.message}`, "error");
        alert(`Could not delete file: ${err.message}`);
        return false;
      }
    } else if (this.currentProject.type === 'fsa') {
      try {
        await this.deleteFsaFile(path);
      } catch (err) {
        if (window.Terminal) window.Terminal.log(`FSA delete error: ${err.message}`, "error");
        alert(`Could not delete file: ${err.message}`);
        return false;
      }
    }

    delete this.files[path];
    if (window.Editor) {
      window.Editor.closeTab(path);
    }
    if (this.activeFilePath === path) {
      const remaining = Object.keys(this.files).find(p => !this.files[p].isDir);
      if (remaining) {
        this.openFile(remaining);
      } else {
        this.activeFilePath = null;
        if (window.Editor) window.Editor.clear();
      }
    }

    if (window.Terminal) window.Terminal.log(`Deleted file: ${path}`, "fs");
    await this.refreshProject();
    return true;
  },

  /**
   * Applies a batch of AI proposals directly into the active project folder.
   * Preserves exact directory structure.
   * Returns { success: boolean, applied: Array, mainFile: string|null, error?: string }
   */
  async applyChangeProposals(proposals) {
    if (!this.hasProject()) {
      throw new Error("No Project Directory selected. Please choose a folder from your phone first.");
    }
    if (!proposals || proposals.length === 0) {
      throw new Error("No changes were proposed.");
    }

    const cleanProposals = proposals.map(p => ({
      action: (p.action || 'MODIFY').toUpperCase(),
      filePath: p.filePath.trim().replace(/^[\/\\]+/, ''),
      content: p.proposedContent || ''
    }));

    if (this.currentProject.type === 'native' && this.currentProject.rootUri) {
      // Use native bridge batch executor for atomic, high-performance disk writes
      try {
        const result = await window.Bridge.applyBatchChanges(this.currentProject.rootUri, cleanProposals);
        if (!result.success) {
          throw new Error(result.error || "Failed to apply changes to project folder");
        }

        // Re-load the freshly scanned tree returned by native bridge
        if (result.tree) {
          this.loadAndroidDirectory(result.tree, this.currentProject.name, this.currentProject.rootUri, true);
        } else {
          await this.refreshProject();
        }

        // Identify main changed file
        const mainFile = this.determineMainChangedFile(cleanProposals);
        if (mainFile) {
          await this.openFile(mainFile);
        }

        return {
          success: true,
          applied: cleanProposals,
          mainFile
        };
      } catch (err) {
        throw new Error(`File operation failed: ${err.message}`);
      }
    } else if (this.currentProject.type === 'fsa' && this.currentProject.handle) {
      // Browser FSA sequential apply
      const applied = [];
      for (const p of cleanProposals) {
        try {
          if (p.action === 'DELETE') {
            await this.deleteFsaFile(p.filePath);
          } else {
            await this.writeFsaFile(p.filePath, p.content);
          }
          applied.push(p);
        } catch (err) {
          throw new Error(`Failed on "${p.filePath}": ${err.message}`);
        }
      }

      await this.refreshProject();

      const mainFile = this.determineMainChangedFile(cleanProposals);
      if (mainFile) {
        await this.openFile(mainFile);
      }

      return {
        success: true,
        applied,
        mainFile
      };
    } else {
      // In-memory fallback
      for (const p of cleanProposals) {
        if (p.action === 'DELETE') {
          delete this.files[p.filePath];
        } else {
          this.files[p.filePath] = {
            name: p.filePath.split('/').pop(),
            path: p.filePath,
            content: p.content,
            isDir: false,
            isDirty: false
          };
        }
      }
      const mainFile = this.determineMainChangedFile(cleanProposals);
      if (mainFile) await this.openFile(mainFile);
      this.notify();
      return { success: true, applied: cleanProposals, mainFile };
    }
  },

  determineMainChangedFile(proposals) {
    if (!proposals || proposals.length === 0) return null;
    // Prefer index.html, main.js, app.js, or the first non-delete proposal
    const nonDeletes = proposals.filter(p => p.action !== 'DELETE');
    if (nonDeletes.length === 0) return null;

    const html = nonDeletes.find(p => p.filePath.toLowerCase().endsWith('index.html') || p.filePath.toLowerCase().endsWith('.html'));
    if (html) return html.filePath;

    const mainJs = nonDeletes.find(p => p.filePath.toLowerCase().includes('main') || p.filePath.toLowerCase().includes('app'));
    if (mainJs) return mainJs.filePath;

    return nonDeletes[0].filePath;
  },

  async writeFsaFile(path, content) {
    const parts = path.split('/');
    let currentHandle = this.currentProject.handle;

    for (let i = 0; i < parts.size - 1; i++) {
      const dirName = parts[i];
      if (!dirName) continue;
      currentHandle = await currentHandle.getDirectoryHandle(dirName, { create: true });
    }

    const fileName = parts[parts.length - 1];
    const fileHandle = await currentHandle.getFileHandle(fileName, { create: true });
    const writable = await fileHandle.createWritable();
    await writable.write(content);
    await writable.close();
  },

  async deleteFsaFile(path) {
    const parts = path.split('/');
    let currentHandle = this.currentProject.handle;

    for (let i = 0; i < parts.size - 1; i++) {
      const dirName = parts[i];
      if (!dirName) continue;
      currentHandle = await currentHandle.getDirectoryHandle(dirName);
    }

    const fileName = parts[parts.length - 1];
    await currentHandle.removeEntry(fileName);
  },

  getFileContent(path) {
    return this.files[path] ? this.files[path].content : null;
  },

  async loadAllFilesContent() {
    const filePaths = Object.keys(this.files).filter(p => !this.files[p].isDir);
    const result = {};

    for (const p of filePaths) {
      const file = this.files[p];
      if (file.content === null) {
        try {
          if (this.currentProject && this.currentProject.type === 'native' && this.currentProject.rootUri) {
            file.content = await window.Bridge.readRelativeFile(this.currentProject.rootUri, p);
          } else if (this.currentProject && this.currentProject.type === 'fsa' && file.handle) {
            const fObj = await file.handle.getFile();
            file.content = await fObj.text();
          } else {
            file.content = "";
          }
        } catch (err) {
          console.warn("Failed to preload content for", p, err);
          file.content = "";
        }
      }
      result[p] = file.content || "";
    }
    return result;
  },

  getAllFiles() {
    return Object.values(this.files);
  },

  getProjectStructureSummary() {
    if (!this.hasProject()) return "No project folder selected.";
    const fileList = Object.keys(this.files).filter(p => !this.files[p].isDir);
    if (fileList.length === 0) return "Project folder is currently empty.";
    return fileList.map(p => `- ${p}`).join('\n');
  },

  getLanguage(filename) {
    if (!filename) return 'text';
    const ext = filename.split('.').pop().toLowerCase();
    const map = {
      html: 'html', htm: 'html',
      css: 'css', scss: 'css',
      js: 'javascript', mjs: 'javascript', jsx: 'javascript',
      ts: 'typescript', tsx: 'typescript',
      json: 'json',
      py: 'python',
      kt: 'kotlin', kts: 'kotlin',
      java: 'java',
      php: 'php',
      xml: 'xml', svg: 'xml',
      md: 'markdown', markdown: 'markdown',
      yml: 'yaml', yaml: 'yaml',
      sh: 'shell', bash: 'shell', zsh: 'shell',
      sql: 'sql', c: 'c', cpp: 'cpp', rs: 'rust', go: 'go'
    };
    return map[ext] || 'text';
  }
};

window.FileSystem = FileSystem;
