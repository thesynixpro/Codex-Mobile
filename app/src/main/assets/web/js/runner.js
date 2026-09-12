// Codex Mobile - Code Execution & Web Preview Runtime
const Runner = {
  activeHtmlFile: null,
  iframeEl: null,
  consoleEntries: [],
  deviceMode: 'responsive', // 'responsive' | 'mobile' | 'tablet'

  init() {
    this.createPreviewModalDOM();
    this.bindEvents();
  },

  createPreviewModalDOM() {
    if (document.getElementById('runner-modal')) return;

    const modal = document.createElement('div');
    modal.id = 'runner-modal';
    modal.className = 'runner-modal-overlay';
    modal.innerHTML = `
      <div class="runner-container">
        <!-- Runner Top Navigation Bar -->
        <div class="runner-header">
          <div class="runner-header-left">
            <span class="runner-badge">${Icons.play} Live Preview</span>
            <select id="runner-file-select" class="runner-file-dropdown" title="Select entry file"></select>
            <button type="button" class="btn btn-sm btn-icon" id="runner-refresh-btn" title="Reload / Refresh Preview">
              <span class="btn-icon-svg">${Icons.refresh}</span>
            </button>
          </div>

          <div class="runner-header-center">
            <div class="device-switcher">
              <button type="button" class="device-btn active" data-mode="responsive" title="Fit to Screen">Full</button>
              <button type="button" class="device-btn" data-mode="mobile" title="Phone View (390px)">Phone</button>
              <button type="button" class="device-btn" data-mode="tablet" title="Tablet View (768px)">Tablet</button>
            </div>
          </div>

          <div class="runner-header-right">
            <button type="button" class="btn btn-sm btn-outline" id="runner-toggle-console-btn" title="Toggle Console Output">
              <span class="btn-icon-svg">${Icons.terminal}</span>
              <span class="console-count-badge" id="runner-console-count">0</span>
            </button>
            <button type="button" class="btn btn-icon btn-sm" id="runner-close-btn" title="Close Preview">
              <span class="btn-icon-svg">${Icons.close}</span>
            </button>
          </div>
        </div>

        <!-- Preview Stage Surface -->
        <div class="runner-body">
          <div class="runner-viewport-wrapper mode-responsive" id="runner-viewport-wrapper">
            <iframe id="runner-iframe" class="runner-iframe" sandbox="allow-scripts allow-modals allow-forms allow-same-origin"></iframe>
          </div>

          <!-- Live Console Output Drawer -->
          <div class="runner-console-pane collapsed" id="runner-console-pane">
            <div class="runner-console-header">
              <div class="runner-console-title">
                <span class="btn-icon-svg">${Icons.terminal}</span>
                <span>Console &amp; Runtime Output</span>
              </div>
              <div class="runner-console-actions">
                <button type="button" class="btn btn-sm btn-icon" id="runner-console-clear-btn" title="Clear Console">
                  <span class="btn-icon-svg">${Icons.trash}</span>
                </button>
                <button type="button" class="btn btn-sm btn-icon" id="runner-console-close-btn" title="Hide Console">
                  <span class="btn-icon-svg">${Icons.close}</span>
                </button>
              </div>
            </div>
            <div class="runner-console-logs" id="runner-console-logs">
              <div class="console-empty">No runtime logs yet. Console output and errors appear here.</div>
            </div>
          </div>
        </div>
      </div>
    `;

    document.body.appendChild(modal);

    // Also inject the Termux Assistant Modal for non-web projects
    this.createTermuxModalDOM();
  },

  createTermuxModalDOM() {
    if (document.getElementById('termux-runtime-modal')) return;

    const modal = document.createElement('div');
    modal.id = 'termux-runtime-modal';
    modal.className = 'modal-overlay';
    modal.innerHTML = `
      <div class="modal-container termux-modal">
        <div class="modal-header">
          <div style="display:flex; align-items:center; gap:8px;">
            <span class="btn-icon-svg">${Icons.terminal}</span>
            <span>Native Runtime Execution (Termux)</span>
          </div>
          <button type="button" class="btn btn-icon btn-sm" id="termux-modal-close">
            <span class="btn-icon-svg">${Icons.close}</span>
          </button>
        </div>
        <div class="modal-content">
          <div class="termux-info-banner">
            <span class="btn-icon-svg">${Icons.info}</span>
            <div>
              <strong>Native Environment Required</strong><br>
              This project uses <span id="termux-detected-lang" style="font-weight:600; color:var(--accent);">Python / Native</span> which requires a real Linux environment and cannot execute inside an isolated web browser WebView.
            </div>
          </div>

          <div class="termux-step-section">
            <div class="termux-step-title">1. Recommended Termux Command:</div>
            <div class="termux-code-block">
              <code id="termux-command-text">python3 main.py</code>
              <button type="button" class="btn btn-sm btn-outline termux-copy-btn" id="termux-copy-cmd-btn">
                <span class="btn-icon-svg">${Icons.copy}</span>
                <span>Copy</span>
              </button>
            </div>
          </div>

          <div class="termux-step-section">
            <div class="termux-step-title">2. One-Time Termux Setup:</div>
            <div class="termux-code-block">
              <code id="termux-setup-text">pkg update -y && pkg install -y python git</code>
              <button type="button" class="btn btn-sm btn-outline termux-copy-btn" id="termux-copy-setup-btn">
                <span class="btn-icon-svg">${Icons.copy}</span>
                <span>Copy</span>
              </button>
            </div>
          </div>

          <div class="termux-instructions">
            <p><strong>How to run on Android:</strong></p>
            <ol>
              <li>Open the <strong>Termux</strong> app on your Android phone.</li>
              <li>Navigate to your project directory (e.g. <code>cd /sdcard/...</code> or Termux home).</li>
              <li>Paste and run the commands above.</li>
            </ol>
          </div>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-primary" id="termux-modal-ok">Understood</button>
        </div>
      </div>
    `;

    document.body.appendChild(modal);
  },

  bindEvents() {
    const modal = document.getElementById('runner-modal');
    const closeBtn = document.getElementById('runner-close-btn');
    const refreshBtn = document.getElementById('runner-refresh-btn');
    const fileSelect = document.getElementById('runner-file-select');
    const toggleConsoleBtn = document.getElementById('runner-toggle-console-btn');
    const consoleClearBtn = document.getElementById('runner-console-clear-btn');
    const consoleCloseBtn = document.getElementById('runner-console-close-btn');

    if (closeBtn) closeBtn.addEventListener('click', () => this.close());
    if (refreshBtn) refreshBtn.addEventListener('click', () => this.reload());

    if (fileSelect) {
      fileSelect.addEventListener('change', (e) => {
        this.activeHtmlFile = e.target.value;
        this.renderPreview();
      });
    }

    // Device switcher buttons
    document.querySelectorAll('.device-switcher .device-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('.device-switcher .device-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        const mode = btn.getAttribute('data-mode');
        this.setDeviceMode(mode);
      });
    });

    if (toggleConsoleBtn) {
      toggleConsoleBtn.addEventListener('click', () => this.toggleConsole());
    }
    if (consoleClearBtn) {
      consoleClearBtn.addEventListener('click', () => this.clearConsole());
    }
    if (consoleCloseBtn) {
      consoleCloseBtn.addEventListener('click', () => this.toggleConsole(false));
    }

    // Termux modal buttons
    const termuxModal = document.getElementById('termux-runtime-modal');
    const termuxClose = document.getElementById('termux-modal-close');
    const termuxOk = document.getElementById('termux-modal-ok');
    const termuxCopyCmd = document.getElementById('termux-copy-cmd-btn');
    const termuxCopySetup = document.getElementById('termux-copy-setup-btn');

    const closeTermux = () => { if (termuxModal) termuxModal.classList.remove('active'); };
    if (termuxClose) termuxClose.addEventListener('click', closeTermux);
    if (termuxOk) termuxOk.addEventListener('click', closeTermux);

    if (termuxCopyCmd) {
      termuxCopyCmd.addEventListener('click', () => {
        const text = document.getElementById('termux-command-text').innerText;
        navigator.clipboard.writeText(text).then(() => {
          termuxCopyCmd.innerHTML = `<span class="btn-icon-svg">${Icons.check}</span><span>Copied</span>`;
          setTimeout(() => {
            termuxCopyCmd.innerHTML = `<span class="btn-icon-svg">${Icons.copy}</span><span>Copy</span>`;
          }, 2000);
        });
      });
    }

    if (termuxCopySetup) {
      termuxCopySetup.addEventListener('click', () => {
        const text = document.getElementById('termux-setup-text').innerText;
        navigator.clipboard.writeText(text).then(() => {
          termuxCopySetup.innerHTML = `<span class="btn-icon-svg">${Icons.check}</span><span>Copied</span>`;
          setTimeout(() => {
            termuxCopySetup.innerHTML = `<span class="btn-icon-svg">${Icons.copy}</span><span>Copy</span>`;
          }, 2000);
        });
      });
    }

    // Receive console messages from sandbox iframe
    window.addEventListener('message', (event) => {
      if (event.data && event.data.__codex_runner_log) {
        this.addConsoleEntry(event.data.type, event.data.args);
      }
    });
  },

  async runProject() {
    if (!window.FileSystem.hasProject()) {
      if (window.App && window.App.showToast) {
        window.App.showToast("Please choose a Project Directory first.");
      }
      window.FileSystem.openProjectDirectory();
      return;
    }

    // Check project contents
    const allFiles = window.FileSystem.getAllFiles().filter(f => !f.isDir);
    const htmlFiles = allFiles.filter(f => f.path.toLowerCase().endsWith('.html') || f.path.toLowerCase().endsWith('.htm'));

    if (htmlFiles.length === 0) {
      // Check if project has Python, Node, Rust, C, Java, etc.
      this.handleNonWebProject(allFiles);
      return;
    }

    // Determine initial entry point
    if (!this.activeHtmlFile || !htmlFiles.some(f => f.path === this.activeHtmlFile)) {
      const indexHtml = htmlFiles.find(f => f.path.toLowerCase() === 'index.html' || f.path.toLowerCase().endsWith('/index.html'));
      this.activeHtmlFile = indexHtml ? indexHtml.path : htmlFiles[0].path;
    }

    // Populate dropdown
    const selectEl = document.getElementById('runner-file-select');
    if (selectEl) {
      selectEl.innerHTML = '';
      for (const hf of htmlFiles) {
        const opt = document.createElement('option');
        opt.value = hf.path;
        opt.textContent = hf.path;
        opt.selected = (hf.path === this.activeHtmlFile);
        selectEl.appendChild(opt);
      }
    }

    // Show modal
    const modal = document.getElementById('runner-modal');
    if (modal) modal.classList.add('active');

    // Clear logs for fresh run
    this.clearConsole();

    // Render preview
    await this.renderPreview();
  },

  handleNonWebProject(files) {
    const filePaths = files.map(f => f.path.toLowerCase());
    let lang = "Native CLI Project";
    let cmd = "ls -la";
    let setup = "pkg update -y";

    if (filePaths.some(p => p.endsWith('.py'))) {
      lang = "Python 3";
      const mainPy = files.find(f => f.path.endsWith('.py'))?.path || "main.py";
      cmd = `python3 ${mainPy}`;
      setup = "pkg update -y && pkg install -y python";
    } else if (filePaths.some(p => p.endsWith('.js') || p.endsWith('.ts') || p.endsWith('package.json'))) {
      lang = "Node.js";
      const mainJs = files.find(f => f.path.endsWith('.js'))?.path || "index.js";
      cmd = `node ${mainJs}`;
      setup = "pkg update -y && pkg install -y nodejs";
    } else if (filePaths.some(p => p.endsWith('.c') || p.endsWith('.cpp'))) {
      lang = "C/C++";
      const mainC = files.find(f => f.path.endsWith('.c') || f.path.endsWith('.cpp'))?.path || "main.c";
      cmd = `clang ${mainC} -o app && ./app`;
      setup = "pkg update -y && pkg install -y clang make";
    } else if (filePaths.some(p => p.endsWith('.rs'))) {
      lang = "Rust";
      cmd = `rustc main.rs && ./main`;
      setup = "pkg update -y && pkg install -y rust";
    } else if (filePaths.some(p => p.endsWith('.kt') || p.endsWith('.java') || p.endsWith('build.gradle') || p.endsWith('build.gradle.kts'))) {
      lang = "Kotlin / Java Gradle";
      cmd = `./gradlew build`;
      setup = "pkg update -y && pkg install -y openjdk-17";
    } else if (filePaths.some(p => p.endsWith('.sh'))) {
      lang = "Bash / Shell";
      const mainSh = files.find(f => f.path.endsWith('.sh'))?.path || "script.sh";
      cmd = `bash ${mainSh}`;
      setup = "pkg update -y && pkg install -y bash coreutils";
    }

    document.getElementById('termux-detected-lang').textContent = lang;
    document.getElementById('termux-command-text').textContent = cmd;
    document.getElementById('termux-setup-text').textContent = setup;

    const termuxModal = document.getElementById('termux-runtime-modal');
    if (termuxModal) termuxModal.classList.add('active');

    if (window.Terminal) {
      window.Terminal.log(`Project requires native environment (${lang}). Termux execution commands provided.`, "system");
    }
  },

  async renderPreview() {
    const iframe = document.getElementById('runner-iframe');
    if (!iframe) return;

    // Preload all project files with contents
    const filesMap = await window.FileSystem.loadAllFilesContent();

    let htmlContent = filesMap[this.activeHtmlFile] || "<h1>No Content</h1>";

    // Build the virtual sandbox document
    const assembledHtml = this.assembleVirtualDocument(htmlContent, filesMap, this.activeHtmlFile);

    // Write to iframe
    iframe.srcdoc = assembledHtml;

    if (window.Terminal) {
      window.Terminal.log(`Preview updated: ${this.activeHtmlFile}`, "system");
    }
  },

  assembleVirtualDocument(rawHtml, filesMap, activePath) {
    const basePath = activePath.includes('/') ? activePath.substring(0, activePath.lastIndexOf('/') + 1) : '';

    const resolveRelative = (rel) => {
      if (!rel || rel.startsWith('http://') || rel.startsWith('https://') || rel.startsWith('data:') || rel.startsWith('#') || rel.startsWith('//')) {
        return null;
      }
      let target = (basePath + rel).replace(/^\.\//, '');
      // Handle ../
      const parts = target.split('/');
      const cleanParts = [];
      for (const p of parts) {
        if (p === '..') cleanParts.pop();
        else if (p !== '.' && p !== '') cleanParts.push(p);
      }
      return cleanParts.join('/');
    };

    // Virtual console injection script
    const consoleHookScript = `
      <script>
      (function() {
        function send(type, args) {
          try {
            var serialized = [];
            for (var i = 0; i < args.length; i++) {
              var a = args[i];
              if (typeof a === 'object' && a !== null) {
                try { serialized.push(JSON.stringify(a)); }
                catch(e) { serialized.push(String(a)); }
              } else {
                serialized.push(String(a));
              }
            }
            window.parent.postMessage({
              __codex_runner_log: true,
              type: type,
              args: serialized
            }, '*');
          } catch(e) {}
        }

        var _log = console.log, _warn = console.warn, _error = console.error, _info = console.info;
        console.log = function() { send('log', arguments); _log.apply(console, arguments); };
        console.warn = function() { send('warn', arguments); _warn.apply(console, arguments); };
        console.error = function() { send('error', arguments); _error.apply(console, arguments); };
        console.info = function() { send('info', arguments); _info.apply(console, arguments); };

        window.onerror = function(msg, url, line, col, err) {
          send('error', [msg + (line ? ' (line ' + line + ')' : '')]);
          return false;
        };

        window.addEventListener('unhandledrejection', function(event) {
          send('error', ['Unhandled Promise Rejection: ' + (event.reason ? event.reason.message || event.reason : 'Unknown')]);
        });
      })();
      </script>
    `;

    // Parse and rewrite HTML links to local virtual files
    let modified = rawHtml;

    // Inject console hooks into head
    if (modified.includes('<head>')) {
      modified = modified.replace('<head>', '<head>' + consoleHookScript);
    } else {
      modified = consoleHookScript + modified;
    }

    // Inline stylesheets
    modified = modified.replace(/<link\s+[^>]*rel=["']stylesheet["'][^>]*href=["']([^"']+)["'][^>]*>/gi, (match, href) => {
      const resolved = resolveRelative(href);
      if (resolved && filesMap[resolved] !== undefined) {
        return `<style data-source="${resolved}">\n${filesMap[resolved]}\n</style>`;
      }
      return match;
    });

    // Inline script tags
    modified = modified.replace(/<script\s+[^>]*src=["']([^"']+)["'][^>]*>\s*<\/script>/gi, (match, src) => {
      const resolved = resolveRelative(src);
      if (resolved && filesMap[resolved] !== undefined) {
        return `<script data-source="${resolved}">\n${filesMap[resolved]}\n</script>`;
      }
      return match;
    });

    return modified;
  },

  reload() {
    this.renderPreview();
  },

  setDeviceMode(mode) {
    this.deviceMode = mode;
    const wrapper = document.getElementById('runner-viewport-wrapper');
    if (!wrapper) return;

    wrapper.classList.remove('mode-responsive', 'mode-mobile', 'mode-tablet');
    wrapper.classList.add(`mode-${mode}`);
  },

  addConsoleEntry(type, args) {
    const logsContainer = document.getElementById('runner-console-logs');
    if (!logsContainer) return;

    // Remove empty placeholder
    const empty = logsContainer.querySelector('.console-empty');
    if (empty) empty.remove();

    const entry = document.createElement('div');
    entry.className = `console-entry entry-${type}`;

    const iconSpan = document.createElement('span');
    iconSpan.className = 'entry-icon';
    iconSpan.innerHTML = type === 'error' ? Icons.alertTriangle : (type === 'warn' ? Icons.info : Icons.chevronRight);
    entry.appendChild(iconSpan);

    const textSpan = document.createElement('span');
    textSpan.className = 'entry-text';
    textSpan.textContent = args.join(' ');
    entry.appendChild(textSpan);

    logsContainer.appendChild(entry);
    logsContainer.scrollTop = logsContainer.scrollHeight;

    // Update count badge
    this.consoleEntries.push({ type, text: args.join(' ') });
    const countBadge = document.getElementById('runner-console-count');
    if (countBadge) {
      countBadge.textContent = this.consoleEntries.length;
      if (type === 'error') countBadge.classList.add('has-error');
    }
  },

  clearConsole() {
    this.consoleEntries = [];
    const logsContainer = document.getElementById('runner-console-logs');
    if (logsContainer) {
      logsContainer.innerHTML = '<div class="console-empty">Console cleared. Output and errors appear here.</div>';
    }
    const countBadge = document.getElementById('runner-console-count');
    if (countBadge) {
      countBadge.textContent = '0';
      countBadge.classList.remove('has-error');
    }
  },

  toggleConsole(forceState) {
    const pane = document.getElementById('runner-console-pane');
    if (!pane) return;
    if (typeof forceState === 'boolean') {
      pane.classList.toggle('collapsed', !forceState);
    } else {
      pane.classList.toggle('collapsed');
    }
  },

  close() {
    const modal = document.getElementById('runner-modal');
    if (modal) modal.classList.remove('active');
  }
};

window.Runner = Runner;
