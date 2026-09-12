// Codex Mobile - Developer Terminal & Log Console
const Terminal = {
  logs: [], // array of { text, type: 'info' | 'system' | 'api' | 'fs' | 'error' | 'success', timestamp }
  outputEl: null,
  inputEl: null,
  commandHistory: [],
  historyIndex: -1,

  init() {
    this.outputEl = document.getElementById('terminal-output');
    this.inputEl = document.getElementById('terminal-input');

    this.bindEvents();
    this.log("Codex Developer Console Initialized", "system");
    this.log("Type 'help' for available workspace and Termux commands.", "info");
  },

  bindEvents() {
    if (!this.inputEl) return;

    this.inputEl.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        const cmd = this.inputEl.value.trim();
        if (cmd) {
          this.executeCommand(cmd);
          this.commandHistory.push(cmd);
          this.historyIndex = this.commandHistory.length;
          this.inputEl.value = '';
        }
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        if (this.historyIndex > 0) {
          this.historyIndex--;
          this.inputEl.value = this.commandHistory[this.historyIndex];
        }
      } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        if (this.historyIndex < this.commandHistory.length - 1) {
          this.historyIndex++;
          this.inputEl.value = this.commandHistory[this.historyIndex];
        } else {
          this.historyIndex = this.commandHistory.length;
          this.inputEl.value = '';
        }
      }
    });

    const clearBtn = document.getElementById('terminal-clear-btn');
    if (clearBtn) {
      clearBtn.addEventListener('click', () => this.clear());
    }

    const copyLogsBtn = document.getElementById('terminal-copy-btn');
    if (copyLogsBtn) {
      copyLogsBtn.addEventListener('click', () => this.copyLogs());
    }
  },

  log(text, type = 'info') {
    const time = new Date().toTimeString().split(' ')[0];
    const entry = { text, type, time };
    this.logs.push(entry);

    if (this.outputEl) {
      const line = document.createElement('div');
      line.className = `terminal-line terminal-${type}`;
      line.innerHTML = `<span class="term-time">[${time}]</span> <span class="term-tag">${type.toUpperCase()}</span> <span class="term-msg">${this.escapeHtml(text)}</span>`;
      this.outputEl.appendChild(line);
      this.outputEl.scrollTop = this.outputEl.scrollHeight;
    }
  },

  clear() {
    this.logs = [];
    if (this.outputEl) {
      this.outputEl.innerHTML = '';
      this.log("Console cleared.", "system");
    }
  },

  copyLogs() {
    const text = this.logs.map(l => `[${l.time}] [${l.type.toUpperCase()}] ${l.text}`).join('\n');
    navigator.clipboard.writeText(text).then(() => {
      if (window.App) window.App.showToast("Terminal logs copied to clipboard");
    });
  },

  executeCommand(cmdStr) {
    const line = document.createElement('div');
    line.className = 'terminal-line terminal-user-cmd';
    line.innerHTML = `<span class="term-prompt">$</span> <span class="term-cmd-text">${this.escapeHtml(cmdStr)}</span>`;
    if (this.outputEl) {
      this.outputEl.appendChild(line);
    }

    const parts = cmdStr.split(/\s+/);
    const cmd = parts[0].toLowerCase();
    const args = parts.slice(1);

    switch (cmd) {
      case 'help':
        this.log("Available commands:\n" +
          "  help           - Display command overview\n" +
          "  status         - Show IDE environment, active project, and bridge info\n" +
          "  ls             - List project workspace files\n" +
          "  cat <file>     - Display content of specified file\n" +
          "  refresh        - Rescan and reload active project directory\n" +
          "  test-api       - Test connectivity to configured AI endpoint\n" +
          "  termux-info    - Display Termux APK build & setup instructions\n" +
          "  clear          - Clear terminal logs\n" +
          "  date           - Display current device timestamp\n" +
          "  echo <msg>     - Echo message to console", "system");
        break;

      case 'refresh':
      case 'reload':
        if (window.FileSystem && window.FileSystem.hasProject()) {
          this.log(`Rescanning project directory...`, "system");
          window.FileSystem.refreshProject();
        } else {
          this.log("No project folder currently selected.", "error");
        }
        break;

      case 'status':
        const proj = window.FileSystem ? window.FileSystem.currentProject : null;
        const activeFile = window.FileSystem ? window.FileSystem.activeFilePath : 'None';
        const fileCount = window.FileSystem ? Object.keys(window.FileSystem.files).length : 0;
        const cfg = window.Storage ? window.Storage.getConfig() : {};
        const isNative = window.Bridge ? window.Bridge.isAvailable() : false;
        this.log(`Workspace Status:\n` +
          `  Project: ${proj ? proj.name : 'None'} (${proj ? proj.type : 'N/A'})\n` +
          `  Files Count: ${fileCount}\n` +
          `  Active File: ${activeFile}\n` +
          `  AI Base URL: ${cfg.baseUrl}\n` +
          `  AI Model: ${cfg.model}\n` +
          `  Native Bridge: ${isNative ? 'Active (Android Storage & KeyStore ready)' : 'Web fallback mode'}\n` +
          `  Platform: ${navigator.userAgent}`, "info");
        break;

      case 'ls':
        if (!window.FileSystem) {
          this.log("No filesystem available.", "error");
          break;
        }
        const fileList = Object.keys(window.FileSystem.files);
        if (fileList.length === 0) {
          this.log("Workspace is empty.", "info");
        } else {
          this.log(fileList.map(f => (window.FileSystem.files[f].isDir ? `${f}/` : f)).join('  '), "info");
        }
        break;

      case 'cat':
        if (args.length === 0) {
          this.log("Usage: cat <filename>", "error");
          break;
        }
        const targetPath = args[0];
        const content = window.FileSystem ? window.FileSystem.getFileContent(targetPath) : null;
        if (content === null) {
          this.log(`File not found: ${targetPath}`, "error");
        } else {
          this.log(`--- ${targetPath} ---\n${content}`, "info");
        }
        break;

      case 'test-api':
        this.log("Testing configured AI endpoint...", "system");
        if (window.AIClient && window.Storage) {
          window.AIClient.testConnection(window.Storage.getConfig())
            .then(res => {
              if (res.success) {
                this.log(`AI Connection Success: ${res.message} (Latency: ${res.latency})`, "success");
              } else {
                this.log(`AI Connection Failed: ${res.message}`, "error");
              }
            })
            .catch(err => {
              this.log(`API test failure: ${err.message}`, "error");
            });
        }
        break;

      case 'termux-info':
        this.log(`Termux Build Commands:\n` +
          `  1. Install tools in Termux:\n` +
          `     pkg update && pkg install openjdk-17 gradle git nodejs\n` +
          `  2. Clone/navigate to project directory:\n` +
          `     cd ~/codex-mobile\n` +
          `  3. Build debug APK directly on your phone:\n` +
          `     gradle assembleDebug\n` +
          `  4. Generated APK location:\n` +
          `     app/build/outputs/apk/debug/app-debug.apk\n` +
          `  5. Install via Termux:\n` +
          `     termux-open app/build/outputs/apk/debug/app-debug.apk`, "system");
        break;

      case 'clear':
        this.clear();
        break;

      case 'date':
        this.log(new Date().toString(), "info");
        break;

      case 'echo':
        this.log(args.join(' '), "info");
        break;

      default:
        this.log(`Command not recognized: '${cmd}'. Note: This is an IDE developer console. To execute raw shell commands, use Termux or the on-device terminal bridge. Type 'help' for options.`, "error");
        break;
    }
  },

  escapeHtml(str) {
    if (!str) return '';
    return str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }
};

window.Terminal = Terminal;
