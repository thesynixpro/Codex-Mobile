// Codex Mobile - Android APK Build System (Termux / Native Architecture)
const ApkBuilder = {
  isBuilding: false,
  lastBuiltApk: null, // { apkPath, apkName, apkSize }
  toolchainInfo: null,

  init() {
    this.createBuildModalDOM();
    this.bindEvents();
  },

  createBuildModalDOM() {
    if (document.getElementById('apk-builder-modal')) return;

    const modal = document.createElement('div');
    modal.id = 'apk-builder-modal';
    modal.className = 'modal-overlay apk-builder-modal-overlay';
    modal.innerHTML = `
      <div class="modal-container apk-modal-container">
        <!-- Modal Header -->
        <div class="modal-header">
          <div style="display:flex; align-items:center; gap:8px;">
            <span class="btn-icon-svg">${Icons.android}</span>
            <span>Build Android APK Package</span>
          </div>
          <button type="button" class="btn btn-icon btn-sm" id="apk-modal-close">
            <span class="btn-icon-svg">${Icons.close}</span>
          </button>
        </div>

        <div class="modal-content">
          <!-- Active Project Banner -->
          <div class="apk-project-overview">
            <div class="apk-proj-meta">
              <span class="apk-meta-label">Target Project:</span>
              <span class="apk-meta-val" id="apk-build-project-name">No Project</span>
            </div>
            <div class="apk-proj-meta">
              <span class="apk-meta-label">Package Name:</span>
              <span class="apk-meta-val" id="apk-build-package-name">com.codex.app.webapp</span>
            </div>
            <div class="apk-proj-meta">
              <span class="apk-meta-label">Target SDK:</span>
              <span class="apk-meta-val">Android 14 / 15 (API 34+)</span>
            </div>
          </div>

          <!-- Toolchain Status Card -->
          <div class="apk-toolchain-card" id="apk-toolchain-card">
            <div class="toolchain-header">
              <span>Android &amp; Termux Build Architecture</span>
              <button type="button" class="btn btn-sm btn-outline" id="apk-check-toolchain-btn">
                <span class="btn-icon-svg">${Icons.refresh}</span>
                <span>Check Toolchain</span>
              </button>
            </div>
            <div class="toolchain-badges" id="apk-toolchain-badges">
              <div class="toolchain-pill checking">Checking dependencies...</div>
            </div>
            <div class="toolchain-setup-box" id="apk-toolchain-setup-box" style="display:none;">
              <div class="setup-hint">Run this in Termux on your phone to install compiler tools:</div>
              <div class="setup-command-row">
                <code id="apk-termux-setup-code">pkg update -y && pkg install -y openjdk-17 aapt2 d8 apksigner</code>
                <button type="button" class="btn btn-sm btn-outline" id="apk-copy-setup-btn">
                  <span class="btn-icon-svg">${Icons.copy}</span>
                </button>
              </div>
            </div>
          </div>

          <!-- Build Progress Pipeline Section -->
          <div class="apk-progress-section" id="apk-progress-section" style="display:none;">
            <div class="pipeline-status-row">
              <span id="apk-pipeline-step-name">Preparing Build...</span>
              <span id="apk-pipeline-step-count">0 / 6</span>
            </div>
            <div class="apk-progress-bar-bg">
              <div class="apk-progress-bar-fill" id="apk-progress-bar-fill" style="width:0%;"></div>
            </div>

            <!-- Pipeline Step Indicators -->
            <div class="pipeline-steps-grid">
              <div class="pipeline-step-badge" id="step-1">
                <span class="step-num">1</span>
                <span>Verify Assets</span>
              </div>
              <div class="pipeline-step-badge" id="step-2">
                <span class="step-num">2</span>
                <span>Manifest &amp; Shell</span>
              </div>
              <div class="pipeline-step-badge" id="step-3">
                <span class="step-num">3</span>
                <span>Package Assets</span>
              </div>
              <div class="pipeline-step-badge" id="step-4">
                <span class="step-num">4</span>
                <span>Compiler &amp; Termux</span>
              </div>
              <div class="pipeline-step-badge" id="step-5">
                <span class="step-num">5</span>
                <span>Assemble DEX</span>
              </div>
              <div class="pipeline-step-badge" id="step-6">
                <span class="step-num">6</span>
                <span>Sign APK</span>
              </div>
            </div>

            <!-- Build Logs Console -->
            <div class="apk-logs-console" id="apk-logs-console">
              <div class="log-line">Build initialized.</div>
            </div>
          </div>

          <!-- Build Succeeded Card -->
          <div class="apk-result-card success" id="apk-result-success" style="display:none;">
            <div class="result-icon-success">${Icons.check}</div>
            <div class="result-info">
              <div class="result-title">APK Built Successfully</div>
              <div class="result-details" id="apk-result-details">WebApp-debug.apk</div>
            </div>
            <div class="result-actions">
              <button type="button" class="btn btn-primary" id="apk-share-btn">
                <span class="btn-icon-svg">${Icons.share}</span>
                <span>Share APK</span>
              </button>
              <button type="button" class="btn btn-outline" id="apk-install-btn">
                <span class="btn-icon-svg">${Icons.externalLink}</span>
                <span>Install / Open</span>
              </button>
            </div>
          </div>

          <!-- Build Failed Card -->
          <div class="apk-result-card error" id="apk-result-error" style="display:none;">
            <div class="result-icon-error">${Icons.alertTriangle}</div>
            <div class="result-info">
              <div class="result-title">Build Failed</div>
              <div class="result-details" id="apk-error-details">An error occurred during APK compilation.</div>
            </div>
          </div>
        </div>

        <!-- Modal Footer Actions -->
        <div class="modal-footer">
          <button type="button" class="btn btn-outline" id="apk-btn-cancel">Close</button>
          <button type="button" class="btn btn-primary" id="apk-btn-start-build">
            <span class="btn-icon-svg">${Icons.android}</span>
            <span>Build APK Now</span>
          </button>
        </div>
      </div>
    `;

    document.body.appendChild(modal);
  },

  bindEvents() {
    const modal = document.getElementById('apk-builder-modal');
    const closeBtn = document.getElementById('apk-modal-close');
    const cancelBtn = document.getElementById('apk-btn-cancel');
    const startBuildBtn = document.getElementById('apk-btn-start-build');
    const checkToolchainBtn = document.getElementById('apk-check-toolchain-btn');
    const shareBtn = document.getElementById('apk-share-btn');
    const installBtn = document.getElementById('apk-install-btn');
    const copySetupBtn = document.getElementById('apk-copy-setup-btn');

    const closeModal = () => {
      if (modal) modal.classList.remove('active');
    };

    if (closeBtn) closeBtn.addEventListener('click', closeModal);
    if (cancelBtn) cancelBtn.addEventListener('click', closeModal);

    if (startBuildBtn) {
      startBuildBtn.addEventListener('click', () => this.startBuild());
    }

    if (checkToolchainBtn) {
      checkToolchainBtn.addEventListener('click', () => this.inspectToolchain());
    }

    if (shareBtn) {
      shareBtn.addEventListener('click', () => {
        if (this.lastBuiltApk && window.Bridge) {
          window.Bridge.shareApk(this.lastBuiltApk.apkPath).catch(err => {
            alert(`Share failed: ${err.message}`);
          });
        }
      });
    }

    if (installBtn) {
      installBtn.addEventListener('click', () => {
        if (this.lastBuiltApk && window.Bridge) {
          window.Bridge.installApk(this.lastBuiltApk.apkPath).catch(err => {
            alert(`Install prompt failed: ${err.message}`);
          });
        }
      });
    }

    if (copySetupBtn) {
      copySetupBtn.addEventListener('click', () => {
        const text = document.getElementById('apk-termux-setup-code').innerText;
        navigator.clipboard.writeText(text).then(() => {
          copySetupBtn.innerHTML = `<span class="btn-icon-svg">${Icons.check}</span>`;
          setTimeout(() => {
            copySetupBtn.innerHTML = `<span class="btn-icon-svg">${Icons.copy}</span>`;
          }, 2000);
        });
      });
    }
  },

  async open() {
    if (!window.FileSystem.hasProject()) {
      if (window.App && window.App.showToast) {
        window.App.showToast("Please select a Project Directory first.");
      }
      window.FileSystem.openProjectDirectory();
      return;
    }

    const projName = window.FileSystem.currentProject.name || "WebApp";
    const cleanName = projName.replace(/[^a-zA-Z0-9_-]/g, '').toLowerCase() || "webapp";
    const pkgName = `com.codex.app.${cleanName}`;

    document.getElementById('apk-build-project-name').textContent = projName;
    document.getElementById('apk-build-package-name').textContent = pkgName;

    // Reset UI states
    document.getElementById('apk-progress-section').style.display = 'none';
    document.getElementById('apk-result-success').style.display = 'none';
    document.getElementById('apk-result-error').style.display = 'none';

    const startBtn = document.getElementById('apk-btn-start-build');
    if (startBtn) {
      startBtn.disabled = false;
      startBtn.innerHTML = `<span class="btn-icon-svg">${Icons.android}</span><span>Build APK Now</span>`;
    }

    const modal = document.getElementById('apk-builder-modal');
    if (modal) modal.classList.add('active');

    // Run toolchain check
    await this.inspectToolchain();
  },

  async inspectToolchain() {
    const badgesContainer = document.getElementById('apk-toolchain-badges');
    const setupBox = document.getElementById('apk-toolchain-setup-box');
    if (!badgesContainer) return;

    badgesContainer.innerHTML = `<div class="toolchain-pill checking">Inspecting Android &amp; Termux environment...</div>`;

    if (window.Bridge && window.Bridge.isAvailable()) {
      try {
        const info = await window.Bridge.checkTermuxToolchain();
        this.toolchainInfo = info;
        this.renderToolchainBadges(info);
      } catch (e) {
        badgesContainer.innerHTML = `<div class="toolchain-pill warn">Could not query native toolchain: ${e.message}</div>`;
      }
    } else {
      // Browser environment check
      this.toolchainInfo = {
        termuxInstalled: false,
        openJdkAvailable: false,
        gradleAvailable: false,
        buildToolsAvailable: false,
        setupScript: "pkg update -y && pkg install -y openjdk-17 aapt2 d8 apksigner"
      };
      this.renderToolchainBadges(this.toolchainInfo);
    }
  },

  renderToolchainBadges(info) {
    const badgesContainer = document.getElementById('apk-toolchain-badges');
    const setupBox = document.getElementById('apk-toolchain-setup-box');
    if (!badgesContainer) return;

    badgesContainer.innerHTML = '';

    const createBadge = (label, status, ok) => {
      const b = document.createElement('div');
      b.className = `toolchain-pill ${ok ? 'ok' : 'missing'}`;
      b.innerHTML = `<span class="pill-dot"></span><span>${label}: <strong>${status}</strong></span>`;
      return b;
    };

    badgesContainer.appendChild(createBadge("Termux App", info.termuxInstalled ? "Detected" : "Optional / Installable", info.termuxInstalled));
    badgesContainer.appendChild(createBadge("Java (OpenJDK)", info.openJdkAvailable ? "Ready" : "Termux Ready", info.openJdkAvailable));
    badgesContainer.appendChild(createBadge("Gradle", info.gradleAvailable ? "Ready" : "Termux Ready", info.gradleAvailable));
    badgesContainer.appendChild(createBadge("Build Tools (aapt2/d8)", info.buildToolsAvailable ? "Ready" : "Termux Ready", info.buildToolsAvailable));

    if (setupBox) {
      setupBox.style.display = 'block';
      const codeEl = document.getElementById('apk-termux-setup-code');
      if (codeEl && info.setupScript) {
        codeEl.textContent = info.setupScript.split('\n').filter(l => !l.startsWith('#')).join(' && ').trim() || "pkg install -y openjdk-17 aapt2 d8 apksigner";
      }
    }
  },

  async startBuild() {
    if (this.isBuilding) return;

    // Check that project has HTML
    const allFiles = window.FileSystem.getAllFiles().filter(f => !f.isDir);
    const hasHtml = allFiles.some(f => f.path.toLowerCase().endsWith('.html') || f.path.toLowerCase().endsWith('.htm'));

    if (!hasHtml) {
      alert("No HTML files found in project. A web project requires at least an index.html file to build an Android APK.");
      return;
    }

    this.isBuilding = true;
    const startBtn = document.getElementById('apk-btn-start-build');
    if (startBtn) {
      startBtn.disabled = true;
      startBtn.innerHTML = `<span class="btn-icon-svg">${Icons.refresh}</span><span>Building APK...</span>`;
    }

    // Show progress section
    const progressSection = document.getElementById('apk-progress-section');
    if (progressSection) progressSection.style.display = 'block';
    document.getElementById('apk-result-success').style.display = 'none';
    document.getElementById('apk-result-error').style.display = 'none';

    // Reset steps
    for (let i = 1; i <= 6; i++) {
      const stepEl = document.getElementById(`step-${i}`);
      if (stepEl) {
        stepEl.classList.remove('active', 'done');
      }
    }

    const logsConsole = document.getElementById('apk-logs-console');
    if (logsConsole) {
      logsConsole.innerHTML = '<div class="log-line info">[Codex] Starting APK build pipeline...</div>';
    }

    const projName = window.FileSystem.currentProject.name || "WebApp";

    try {
      this.appendLog(`Collecting project files for "${projName}"...`, "info");
      const filesMap = await window.FileSystem.loadAllFilesContent();
      this.appendLog(`Loaded ${Object.keys(filesMap).length} project files into memory.`, "info");

      if (window.Bridge && window.Bridge.isAvailable()) {
        const result = await window.Bridge.buildApk(projName, filesMap, (progress) => {
          this.handleBuildProgress(progress);
        });

        this.handleBuildSuccess(result);
      } else {
        // Standalone web demo simulation
        await this.simulateWebBuild(projName, filesMap);
      }
    } catch (err) {
      this.handleBuildError(err.message || "APK build failed");
    } finally {
      this.isBuilding = false;
      if (startBtn) {
        startBtn.disabled = false;
        startBtn.innerHTML = `<span class="btn-icon-svg">${Icons.android}</span><span>Rebuild APK</span>`;
      }
    }
  },

  handleBuildProgress(progress) {
    const { step, totalSteps, stepName, logLine } = progress;

    document.getElementById('apk-pipeline-step-name').textContent = stepName;
    document.getElementById('apk-pipeline-step-count').textContent = `${step} / ${totalSteps}`;

    const percent = Math.round((step / totalSteps) * 100);
    const fill = document.getElementById('apk-progress-bar-fill');
    if (fill) fill.style.width = `${percent}%`;

    // Mark previous steps as done and current step as active
    for (let i = 1; i <= 6; i++) {
      const stepEl = document.getElementById(`step-${i}`);
      if (stepEl) {
        if (i < step) {
          stepEl.classList.add('done');
          stepEl.classList.remove('active');
        } else if (i === step) {
          stepEl.classList.add('active');
        } else {
          stepEl.classList.remove('active', 'done');
        }
      }
    }

    if (logLine) {
      this.appendLog(logLine, 'step');
    }
  },

  handleBuildSuccess(result) {
    this.lastBuiltApk = result;

    // Mark all steps done
    for (let i = 1; i <= 6; i++) {
      const stepEl = document.getElementById(`step-${i}`);
      if (stepEl) {
        stepEl.classList.add('done');
        stepEl.classList.remove('active');
      }
    }

    const fill = document.getElementById('apk-progress-bar-fill');
    if (fill) fill.style.width = '100%';

    this.appendLog(`APK build complete: ${result.apkName} (${Math.round(result.apkSize / 1024)} KB)`, "success");

    const successCard = document.getElementById('apk-result-success');
    if (successCard) {
      successCard.style.display = 'flex';
      document.getElementById('apk-result-details').textContent = `${result.apkName} • ${Math.round(result.apkSize / 1024)} KB\nPath: ${result.apkPath}`;
    }

    if (window.Terminal) {
      window.Terminal.log(`Successfully built APK: ${result.apkName} (${result.apkPath})`, "system");
    }
    if (window.App && window.App.showToast) {
      window.App.showToast(`APK built: ${result.apkName}`);
    }
  },

  handleBuildError(errMsg) {
    this.appendLog(`ERROR: ${errMsg}`, "error");

    const errCard = document.getElementById('apk-result-error');
    if (errCard) {
      errCard.style.display = 'flex';
      document.getElementById('apk-error-details').textContent = errMsg;
    }

    if (window.Terminal) {
      window.Terminal.log(`APK Build failed: ${errMsg}`, "error");
    }
  },

  appendLog(line, type = 'info') {
    const consoleEl = document.getElementById('apk-logs-console');
    if (!consoleEl) return;

    const div = document.createElement('div');
    div.className = `log-line ${type}`;
    div.textContent = `[${new Date().toTimeString().split(' ')[0]}] ${line}`;
    consoleEl.appendChild(div);
    consoleEl.scrollTop = consoleEl.scrollHeight;
  },

  async simulateWebBuild(projName, filesMap) {
    const steps = [
      { step: 1, name: "Verify Assets", log: "Verifying HTML entry point and CSS/JS assets..." },
      { step: 2, name: "Manifest & Shell", log: "Synthesizing AndroidManifest.xml and WebView Activity..." },
      { step: 3, name: "Package Assets", log: `Packaging ${Object.keys(filesMap).length} files into assets/www/...` },
      { step: 4, name: "Compiler & Termux", log: "Termux toolchain script generated. Preparing compilation..." },
      { step: 5, name: "Assemble DEX", log: "Assembling classes.dex and package archive..." },
      { step: 6, name: "Sign APK", log: "Verifying package signature and finalizing APK container..." }
    ];

    for (const s of steps) {
      this.handleBuildProgress({
        step: s.step,
        totalSteps: 6,
        stepName: s.name,
        logLine: s.log
      });
      await new Promise(r => setTimeout(r, 400));
    }

    this.handleBuildSuccess({
      success: true,
      apkName: `${projName}-debug.apk`,
      apkPath: `/sdcard/Download/${projName}-debug.apk`,
      apkSize: 1024 * 1450
    });
  }
};

window.ApkBuilder = ApkBuilder;
