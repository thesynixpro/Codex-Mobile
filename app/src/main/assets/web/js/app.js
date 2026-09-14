// Codex Mobile - Main Application Controller
const App = {
  currentView: 'editor', // 'files' | 'editor' | 'chat' | 'terminal' | 'settings'

  init() {
    this.injectIcons();
    this.bindNavigation();
    this.bindProjectDirectoryControls();
    this.bindFileExplorer();
    this.bindChatUI();
    this.bindSettingsUI();
    this.bindAIProvidersUI();
    this.bindViewportAndKeyboard();

    // Initialize submodules
    window.FileSystem.init();
    window.Editor.init();
    window.Terminal.init();
    window.AIClient.init();
    if (window.ChatHistory) window.ChatHistory.init();
    if (window.BackgroundTasks) window.BackgroundTasks.init();
    if (window.Runner) window.Runner.init();
    if (window.ApkBuilder) window.ApkBuilder.init();

    // Subscribe to filesystem changes
    window.FileSystem.subscribe(() => {
      this.syncProjectState();
    });

    // Initial render
    this.syncProjectState();
    this.loadSettingsForm();
    this.renderChatProviderSelect();

    // Check device bridge status
    const devInfo = window.Bridge.getDeviceInfo();
    if (window.Bridge.isAvailable()) {
      window.Terminal.log(`Android environment connected (${devInfo.device || 'Android SDK ' + (devInfo.sdkInt || 36)})`, 'system');
      const bridgeBadge = document.getElementById('native-bridge-indicator');
      if (bridgeBadge) {
        bridgeBadge.classList.add('connected');
        bridgeBadge.title = "Connected to Android Native SAF Bridge";
      }
    }

    // Register back button handler for Android
    window.handleAndroidBack = () => {
      return this.onBackPress() ? "handled" : "unhandled";
    };
  },

  injectIcons() {
    document.querySelectorAll('[data-icon]').forEach(el => {
      const name = el.getAttribute('data-icon');
      if (Icons[name]) el.innerHTML = Icons[name];
    });
  },

  bindNavigation() {
    // Mobile bottom navigation tabs
    const navItems = document.querySelectorAll('.bottom-nav-item');
    navItems.forEach(item => {
      item.addEventListener('click', () => {
        const view = item.getAttribute('data-view');
        this.switchView(view);
      });
    });

    // Run Code Preview button
    const runCodeBtn = document.getElementById('btn-run-code');
    if (runCodeBtn) {
      runCodeBtn.addEventListener('click', () => {
        if (window.Runner) window.Runner.runProject();
      });
    }

    // Build APK button
    const buildApkBtn = document.getElementById('btn-build-apk');
    if (buildApkBtn) {
      buildApkBtn.addEventListener('click', () => {
        if (window.ApkBuilder) window.ApkBuilder.open();
      });
    }

    // Chat History buttons
    const chatHistoryBtn = document.getElementById('btn-chat-history');
    if (chatHistoryBtn) {
      chatHistoryBtn.addEventListener('click', () => {
        if (window.ChatHistory) window.ChatHistory.open();
      });
    }

    const chatHistoryTabBtn = document.getElementById('btn-chat-history-tab');
    if (chatHistoryTabBtn) {
      chatHistoryTabBtn.addEventListener('click', () => {
        if (window.ChatHistory) window.ChatHistory.open();
      });
    }

    const chatNewBtn = document.getElementById('btn-chat-new');
    if (chatNewBtn) {
      chatNewBtn.addEventListener('click', () => {
        if (window.ChatHistory) window.ChatHistory.startNewConversation();
      });
    }

    // Top action bar buttons
    const toggleSidebarBtn = document.getElementById('btn-toggle-sidebar');
    if (toggleSidebarBtn) {
      toggleSidebarBtn.addEventListener('click', () => this.toggleSidebar());
    }

    const toggleChatBtn = document.getElementById('btn-toggle-chat');
    if (toggleChatBtn) {
      toggleChatBtn.addEventListener('click', () => this.toggleChat());
    }

    const toggleTerminalBtn = document.getElementById('btn-toggle-terminal');
    if (toggleTerminalBtn) {
      toggleTerminalBtn.addEventListener('click', () => this.toggleTerminal());
    }

    const saveFileBtn = document.getElementById('btn-save-file');
    if (saveFileBtn) {
      saveFileBtn.addEventListener('click', () => window.Editor.saveCurrentFile());
    }

    const settingsBtn = document.getElementById('btn-open-settings');
    if (settingsBtn) {
      settingsBtn.addEventListener('click', () => this.openSettingsModal());
    }
  },

  bindProjectDirectoryControls() {
    // Top Bar "Choose Folder" / "Change Folder"
    const openDirBtn = document.getElementById('btn-open-directory');
    if (openDirBtn) {
      openDirBtn.addEventListener('click', () => window.FileSystem.openProjectDirectory());
    }

    // Top Bar "Refresh Project"
    const refreshBtn = document.getElementById('btn-refresh-project');
    if (refreshBtn) {
      refreshBtn.addEventListener('click', () => window.FileSystem.refreshProject());
    }

    // Hero prompt overlay "Select Project Directory" button
    const heroBtn = document.getElementById('btn-choose-project-hero');
    if (heroBtn) {
      heroBtn.addEventListener('click', () => window.FileSystem.openProjectDirectory());
    }

    // Sidebar change directory button
    const sidebarChangeBtn = document.getElementById('btn-sidebar-change');
    if (sidebarChangeBtn) {
      sidebarChangeBtn.addEventListener('click', () => window.FileSystem.openProjectDirectory());
    }

    // Sidebar refresh button
    const sidebarRefreshBtn = document.getElementById('btn-sidebar-refresh');
    if (sidebarRefreshBtn) {
      sidebarRefreshBtn.addEventListener('click', () => window.FileSystem.refreshProject());
    }

    // Settings modal change directory button
    const settingsChangeBtn = document.getElementById('btn-settings-change-project');
    if (settingsChangeBtn) {
      settingsChangeBtn.addEventListener('click', () => {
        this.closeSettingsModal();
        window.FileSystem.openProjectDirectory();
      });
    }
  },

  bindFileExplorer() {
    const newFileBtn = document.getElementById('btn-new-file');
    if (newFileBtn) {
      newFileBtn.addEventListener('click', () => this.promptNewFile());
    }
  },

  dismissProjectPrompt() {
    const promptOverlay = document.getElementById('project-prompt-overlay');
    if (promptOverlay) {
      promptOverlay.classList.add('hidden');
      promptOverlay.style.display = 'none';
    }
  },

  showProjectPrompt() {
    const promptOverlay = document.getElementById('project-prompt-overlay');
    if (promptOverlay) {
      promptOverlay.classList.remove('hidden');
      promptOverlay.style.display = 'flex';
    }
  },

  syncProjectState() {
    const hasProj = window.FileSystem.hasProject();

    // Show/hide initial folder prompt overlay
    if (hasProj) {
      this.dismissProjectPrompt();
    } else {
      this.showProjectPrompt();
    }

    this.updateProjectHeader();
    this.renderFileTree();
    this.updateContextChips();
  },

  updateProjectHeader() {
    const projTitle = document.getElementById('project-root-name');
    const openDirLabel = document.getElementById('btn-open-directory-label');
    const settingsProjName = document.getElementById('settings-project-name');

    if (window.FileSystem.hasProject()) {
      const name = window.FileSystem.currentProject.name;
      if (projTitle) projTitle.textContent = name;
      if (openDirLabel) openDirLabel.textContent = "Change Folder";
      if (settingsProjName) settingsProjName.textContent = name;
    } else {
      if (projTitle) projTitle.textContent = "No Project";
      if (openDirLabel) openDirLabel.textContent = "Choose Folder";
      if (settingsProjName) settingsProjName.textContent = "No project folder selected";
    }
  },

  renderFileTree() {
    const treeContainer = document.getElementById('file-tree-container');
    if (!treeContainer) return;

    treeContainer.innerHTML = '';

    if (window.FileSystem.isScanning) {
      treeContainer.innerHTML = `
        <div class="empty-project-box">
          <div class="empty-project-icon spinning">${Icons.refresh}</div>
          <div class="empty-project-title">Scanning Project Folder...</div>
          <div class="empty-project-sub">Reading directory structure and files.</div>
        </div>
      `;
      return;
    }

    if (!window.FileSystem.hasProject()) {
      treeContainer.innerHTML = `
        <div class="empty-project-box">
          <div class="empty-project-icon">${Icons.folderOpen}</div>
          <div class="empty-project-title">No Project Selected</div>
          <div class="empty-project-sub">Choose a folder from your phone storage to browse and edit project files.</div>
          <button type="button" class="btn btn-sm btn-primary" id="btn-tree-open-dir">
            ${Icons.folderOpen} Choose Directory
          </button>
        </div>
      `;
      const openBtn = treeContainer.querySelector('#btn-tree-open-dir');
      if (openBtn) {
        openBtn.addEventListener('click', () => window.FileSystem.openProjectDirectory());
      }
      return;
    }

    const allFiles = window.FileSystem.getAllFiles();

    if (allFiles.length === 0) {
      treeContainer.innerHTML = `
        <div class="empty-project-box">
          <div class="empty-project-icon">${Icons.folder}</div>
          <div class="empty-project-title">Folder is Empty</div>
          <div class="empty-project-sub">"${this.escapeHtml(window.FileSystem.currentProject.name)}" has no files yet. Create a file or ask AI to generate an app.</div>
          <div style="display:flex; flex-direction:column; gap:6px; width:100%;">
            <button type="button" class="btn btn-sm btn-outline" id="btn-empty-new-file">
              ${Icons.plus} New File
            </button>
            <button type="button" class="btn btn-sm btn-primary" id="btn-empty-ask-ai">
              ${Icons.sparkles} Generate App in AI Chat
            </button>
          </div>
        </div>
      `;
      const newFileBtn = treeContainer.querySelector('#btn-empty-new-file');
      if (newFileBtn) newFileBtn.addEventListener('click', () => this.promptNewFile());

      const askAiBtn = treeContainer.querySelector('#btn-empty-ask-ai');
      if (askAiBtn) {
        askAiBtn.addEventListener('click', () => {
          this.switchView('chat');
          const chatInput = document.getElementById('chat-prompt-input');
          if (chatInput) {
            chatInput.value = "Create a responsive web application with index.html, style.css, and app.js";
            chatInput.focus();
          }
        });
      }
      return;
    }

    // Sort: directories first, then alphabetically
    allFiles.sort((a, b) => {
      if (a.isDir && !b.isDir) return -1;
      if (!a.isDir && b.isDir) return 1;
      return a.path.localeCompare(b.path);
    });

    for (const file of allFiles) {
      const itemEl = document.createElement('div');
      itemEl.className = `file-tree-item ${file.isDir ? 'is-dir' : 'is-file'} ${file.path === window.FileSystem.activeFilePath ? 'active' : ''}`;

      const iconSpan = document.createElement('span');
      iconSpan.className = 'tree-item-icon';
      if (file.isDir) {
        iconSpan.innerHTML = Icons.folder;
      } else if (file.name.toLowerCase().endsWith('.apk')) {
        iconSpan.innerHTML = Icons.android;
      } else {
        iconSpan.innerHTML = Icons.fileCode;
      }
      itemEl.appendChild(iconSpan);

      const nameSpan = document.createElement('span');
      nameSpan.className = 'tree-item-name';
      nameSpan.textContent = file.name;
      itemEl.appendChild(nameSpan);

      if (!file.isDir) {
        // Context selector toggle button
        const contextBtn = document.createElement('button');
        contextBtn.type = 'button';
        contextBtn.className = `tree-context-toggle ${window.AIClient.selectedContextFiles.has(file.path) ? 'active' : ''}`;
        contextBtn.title = "Include in AI context";
        contextBtn.innerHTML = Icons.sparkles;
        contextBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          this.toggleFileContext(file.path);
        });
        itemEl.appendChild(contextBtn);

        // Delete button
        const deleteBtn = document.createElement('button');
        deleteBtn.type = 'button';
        deleteBtn.className = 'tree-delete-btn';
        deleteBtn.title = `Delete ${file.name}`;
        deleteBtn.innerHTML = Icons.trash;
        deleteBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          window.FileSystem.deleteFile(file.path);
        });
        itemEl.appendChild(deleteBtn);
      }

      itemEl.addEventListener('click', () => {
        if (!file.isDir) {
          window.FileSystem.openFile(file.path);
          if (window.innerWidth < 768) {
            this.switchView('editor');
          }
        }
      });

      treeContainer.appendChild(itemEl);
    }
  },

  promptNewFile() {
    if (!window.FileSystem.hasProject()) {
      alert("Please select a Project Directory first.");
      window.FileSystem.openProjectDirectory();
      return;
    }
    const filename = prompt("Enter new filename (e.g. index.html, style.css, js/app.js):");
    if (filename && filename.trim()) {
      window.FileSystem.createFile(filename.trim(), "");
      if (window.innerWidth < 768) {
        this.switchView('editor');
      }
    }
  },

  toggleFileContext(path) {
    if (window.AIClient.selectedContextFiles.has(path)) {
      window.AIClient.selectedContextFiles.delete(path);
    } else {
      window.AIClient.selectedContextFiles.add(path);
    }
    this.renderFileTree();
    this.updateContextChips();
  },

  updateContextChips() {
    const container = document.getElementById('chat-context-chips');
    if (!container) return;

    container.innerHTML = '';
    const selected = Array.from(window.AIClient.selectedContextFiles);

    if (selected.length === 0) {
      const activePath = window.FileSystem.activeFilePath;
      if (activePath && !window.AIClient.ignoreActiveFileContext) {
        const chip = document.createElement('div');
        chip.className = 'context-chip auto-chip';
        chip.innerHTML = `
          <span class="chip-icon">${Icons.file}</span>
          <span class="chip-label">${activePath} (active)</span>
          <button type="button" class="chip-remove-btn" title="Detach active file context" aria-label="Detach active file">${Icons.close}</button>
        `;
        chip.querySelector('.chip-remove-btn').addEventListener('click', (e) => {
          e.stopPropagation();
          window.AIClient.ignoreActiveFileContext = true;
          this.updateContextChips();
        });
        container.appendChild(chip);
      }
      return;
    }

    for (const path of selected) {
      const chip = document.createElement('div');
      chip.className = 'context-chip';
      chip.innerHTML = `
        <span class="chip-icon">${Icons.fileCode}</span>
        <span class="chip-label">${path}</span>
        <button type="button" class="chip-remove-btn" aria-label="Remove context">${Icons.close}</button>
      `;
      chip.querySelector('.chip-remove-btn').addEventListener('click', () => {
        this.toggleFileContext(path);
      });
      container.appendChild(chip);
    }
  },

  switchView(viewName) {
    this.currentView = viewName;

    // Update bottom nav active state
    document.querySelectorAll('.bottom-nav-item').forEach(btn => {
      btn.classList.toggle('active', btn.getAttribute('data-view') === viewName);
    });

    const sidebar = document.getElementById('sidebar-panel');
    const editor = document.getElementById('editor-panel');
    const chat = document.getElementById('chat-panel');
    const terminal = document.getElementById('terminal-panel');

    // Remove mobile-active from all panels
    [sidebar, editor, chat, terminal].forEach(p => p && p.classList.remove('mobile-active'));

    if (viewName === 'files' && sidebar) sidebar.classList.add('mobile-active');
    else if (viewName === 'editor' && editor) editor.classList.add('mobile-active');
    else if (viewName === 'chat' && chat) chat.classList.add('mobile-active');
    else if (viewName === 'terminal' && terminal) terminal.classList.add('mobile-active');
    else if (viewName === 'settings') {
      this.openSettingsModal();
      if (editor) editor.classList.add('mobile-active');
    }
  },

  toggleSidebar() {
    const sidebar = document.getElementById('sidebar-panel');
    if (sidebar) sidebar.classList.toggle('collapsed');
  },

  toggleChat() {
    const chat = document.getElementById('chat-panel');
    if (chat) chat.classList.toggle('collapsed');
  },

  toggleTerminal() {
    const term = document.getElementById('terminal-panel');
    if (term) term.classList.toggle('collapsed');
  },

  bindChatUI() {
    const chatInput = document.getElementById('chat-prompt-input');
    const sendBtn = document.getElementById('chat-send-btn');
    const clearChatBtn = document.getElementById('chat-clear-btn');

    const handleSend = () => {
      const text = chatInput.value.trim();
      if (!text || window.AIClient.isGenerating) return;
      chatInput.value = '';
      this.submitUserMessage(text, 'custom');
    };

    if (sendBtn) sendBtn.addEventListener('click', handleSend);
    if (chatInput) {
      chatInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
          e.preventDefault();
          handleSend();
        }
      });
    }

    if (clearChatBtn) {
      clearChatBtn.addEventListener('click', () => {
        window.AIClient.clearHistory();
        const msgList = document.getElementById('chat-messages-list');
        if (msgList) msgList.innerHTML = '';
        this.appendAssistantGreeting();
      });
    }

    // Quick action buttons
    document.querySelectorAll('.chat-action-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const action = btn.getAttribute('data-action');
        const activeFile = window.FileSystem.activeFilePath;
        let promptText = "";
        if (action === 'explain') {
          promptText = activeFile ? `Explain the code in ${activeFile}.` : "Explain the current workspace code.";
        } else if (action === 'fix') {
          promptText = activeFile ? `Review ${activeFile} for errors and edge cases, and propose fixes.` : "Find and fix bugs in current code.";
        } else if (action === 'refactor') {
          promptText = activeFile ? `Refactor ${activeFile} for higher readability and efficiency.` : "Refactor the project code.";
        } else if (action === 'generate') {
          promptText = "Generate a complete application feature with clean code structure.";
        }
        this.submitUserMessage(promptText, action);
      });
    });

    this.appendAssistantGreeting();
  },

  appendAssistantGreeting() {
    const msgList = document.getElementById('chat-messages-list');
    if (!msgList) return;
    const greetingEl = document.createElement('div');
    greetingEl.className = 'chat-message message-assistant';
    greetingEl.innerHTML = `
      <div class="message-header">
        <span class="message-author">${Icons.sparkles} Codex AI Assistant</span>
        <span class="message-time">Ready</span>
      </div>
      <div class="message-body">
        <p>I am your mobile AI coding assistant connected directly to your Android project folder.</p>
        <p>Ask me to write code, create files, or fix bugs. I'll propose exact diffs and write them to your phone storage only when you tap <strong>Apply Changes</strong>.</p>
      </div>
    `;
    msgList.appendChild(greetingEl);
  },

  async submitUserMessage(promptText, actionType) {
    const msgList = document.getElementById('chat-messages-list');
    if (!msgList) return;

    // Check project selected
    if (!window.FileSystem.hasProject()) {
      this.showToast("Please choose a Project Directory first.");
      window.FileSystem.openProjectDirectory();
      return;
    }

    // Append user message
    const userMsgEl = document.createElement('div');
    userMsgEl.className = 'chat-message message-user';
    userMsgEl.innerHTML = `
      <div class="message-header">
        <span class="message-author">You</span>
        <span class="message-time">${new Date().toTimeString().split(' ')[0]}</span>
      </div>
      <div class="message-body">${this.escapeHtml(promptText)}</div>
    `;
    msgList.appendChild(userMsgEl);

    // Persist user message to local Room DB
    if (window.ChatHistory) {
      window.ChatHistory.persistMessage('user', promptText);
    }

    // Check background mode
    const isBgMode = document.getElementById('chat-bg-mode-checkbox')?.checked;
    if (isBgMode && window.BackgroundTasks && window.Bridge && window.Bridge.isAvailable()) {
      const config = window.Storage ? window.Storage.getConfig() : {};
      const fullContext = window.AIClient.buildContextMessage(promptText, actionType);

      const task = await window.BackgroundTasks.startTask({
        conversationId: window.ChatHistory ? window.ChatHistory.currentConversationId : '',
        prompt: promptText,
        fullContext: fullContext,
        actionType: actionType,
        baseUrl: config.baseUrl,
        apiKey: config.apiKey,
        model: config.model,
        systemPrompt: config.systemPrompt
      });

      if (task) {
        const bgNoticeEl = document.createElement('div');
        bgNoticeEl.className = 'chat-message message-assistant';
        bgNoticeEl.innerHTML = `
          <div class="message-header">
            <span class="message-author">${Icons.sparkles} Codex AI (Background Service)</span>
            <span class="message-time">${new Date().toTimeString().split(' ')[0]}</span>
          </div>
          <div class="message-body">
            <div style="padding:6px 0; color:var(--accent);">
              <div style="font-weight:600; margin-bottom:4px;">Dispatched to Android Foreground Service</div>
              <div style="font-size:12px; color:var(--text-secondary); line-height:1.4;">
                This AI generation is running in an isolated Android foreground service so it will not be terminated by Android when you switch apps or lock your phone. Check your notification bar for live status. Results will sync here automatically upon completion.
              </div>
            </div>
          </div>
        `;
        msgList.appendChild(bgNoticeEl);
        msgList.scrollTop = msgList.scrollHeight;
        return;
      }
    }

    // Append loading placeholder
    const loadingMsgEl = document.createElement('div');
    loadingMsgEl.className = 'chat-message message-assistant loading';
    loadingMsgEl.innerHTML = `
      <div class="message-header">
        <span class="message-author">${Icons.sparkles} Codex AI</span>
        <span class="message-time">Analyzing project...</span>
      </div>
      <div class="message-body">
        <div class="loading-dots"><span></span><span></span><span></span></div>
      </div>
    `;
    msgList.appendChild(loadingMsgEl);
    msgList.scrollTop = msgList.scrollHeight;

    try {
      const responseText = await window.AIClient.sendMessage(promptText, actionType);
      loadingMsgEl.classList.remove('loading');

      const timeStr = new Date().toTimeString().split(' ')[0];
      loadingMsgEl.querySelector('.message-time').textContent = timeStr;

      const bodyEl = loadingMsgEl.querySelector('.message-body');
      bodyEl.innerHTML = this.renderMarkdown(responseText);

      // Attach copy code buttons
      bodyEl.querySelectorAll('.code-copy-btn').forEach(btn => {
        btn.addEventListener('click', () => {
          const codeBlock = btn.closest('.code-block-container').querySelector('code');
          if (codeBlock) {
            navigator.clipboard.writeText(codeBlock.innerText).then(() => {
              btn.innerHTML = `${Icons.check} Copied`;
              setTimeout(() => { btn.innerHTML = `${Icons.copy} Copy`; }, 2000);
            });
          }
        });
      });

      // Parse file modification proposals
      const proposals = window.DiffPatch.extractProposals(responseText);

      // Persist assistant message and proposals to local Room DB
      if (window.ChatHistory) {
        window.ChatHistory.persistMessage('assistant', responseText, proposals);
      }

      if (proposals.length > 0) {
        const previewContainer = window.DiffPatch.renderChangePreview(
          proposals,
          async (propsToApply) => {
            // Apply all proposals directly into active project directory
            const result = await window.FileSystem.applyChangeProposals(propsToApply);
            
            // Build summary list of applied files
            const appliedSummary = result.applied.map(p => `- ${p.filePath} (${p.action.toLowerCase()})`).join('\n');
            const projName = window.FileSystem.currentProject.name;

            window.Terminal.log(`Applied ${result.applied.length} changes directly into "${projName}":\n${appliedSummary}`, 'fs');
            App.showToast(`Applied ${result.applied.length} changes to ${projName}`);

            // Append a clear confirmation note in chat
            const confirmEl = document.createElement('div');
            confirmEl.className = 'ai-apply-confirmation-box';
            confirmEl.innerHTML = `
              <div class="confirm-header">${Icons.check} Changes Applied to <strong>${this.escapeHtml(projName)}</strong></div>
              <div class="confirm-list">${this.escapeHtml(appliedSummary).replace(/\n/g, '<br>')}</div>
            `;
            bodyEl.appendChild(confirmEl);

            // Automatically switch to editor on mobile so user sees the newly generated code
            if (window.innerWidth < 768 && result.mainFile) {
              setTimeout(() => {
                this.switchView('editor');
              }, 400);
            }
          },
          (propsToReject) => {
            window.Terminal.log(`Dismissed ${propsToReject.length} proposed changes. No files were modified.`, 'system');
            App.showToast("Changes dismissed");
          }
        );

        bodyEl.appendChild(previewContainer);
      }

    } catch (err) {
      loadingMsgEl.classList.remove('loading');
      loadingMsgEl.classList.add('error');
      loadingMsgEl.querySelector('.message-time').textContent = "Failed";
      const bodyEl = loadingMsgEl.querySelector('.message-body');
      bodyEl.innerHTML = `
        <div class="ai-error-box">
          <div class="ai-error-header">${Icons.alertTriangle} Request Failed</div>
          <div class="ai-error-msg">${this.escapeHtml(err.message)}</div>
          <div class="ai-error-hint">Verify endpoint configuration, API key, and model name in Settings.</div>
        </div>
      `;
    }

    msgList.scrollTop = msgList.scrollHeight;
  },

  renderMarkdown(text) {
    if (!text) return '';
    let html = '';

    const codeBlockRegex = /```([a-zA-Z0-9_\-./:]*)\n([\s\S]*?)```/g;
    let lastIndex = 0;
    let match;

    while ((match = codeBlockRegex.exec(text)) !== null) {
      const prevText = text.substring(lastIndex, match.index);
      html += this.renderInlineMarkdown(prevText);

      const headerMeta = match[1] || '';
      const codeContent = match[2];
      const lang = headerMeta.split(':')[0] || 'code';
      const filepath = headerMeta.includes(':') ? headerMeta.split(':')[1] : '';

      html += `
        <div class="code-block-container">
          <div class="code-block-header">
            <span class="code-block-lang">${this.escapeHtml(filepath || lang)}</span>
            <button type="button" class="btn-icon-text code-copy-btn">${Icons.copy} Copy</button>
          </div>
          <pre class="code-block-pre"><code>${this.escapeHtml(codeContent)}</code></pre>
        </div>
      `;

      lastIndex = match.index + match[0].length;
    }

    html += this.renderInlineMarkdown(text.substring(lastIndex));
    return html;
  },

  renderInlineMarkdown(text) {
    if (!text) return '';
    let out = this.escapeHtml(text);

    // Headers
    out = out.replace(/^### (.*$)/gim, '<h3>$1</h3>');
    out = out.replace(/^## (.*$)/gim, '<h2>$1</h2>');
    out = out.replace(/^# (.*$)/gim, '<h1>$1</h1>');

    // Bold & italic
    out = out.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
    out = out.replace(/\*(.*?)\*/g, '<em>$1</em>');

    // Inline code
    out = out.replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>');

    // Lists
    out = out.replace(/^\s*[-*]\s+(.*$)/gim, '<li>$1</li>');
    out = out.replace(/(<li>.*<\/li>)/s, '<ul>$1</ul>');

    // Paragraphs
    out = out.split('\n\n').map(p => {
      p = p.trim();
      if (!p) return '';
      if (p.startsWith('<h') || p.startsWith('<ul') || p.startsWith('<div')) return p;
      return `<p>${p.replace(/\n/g, '<br>')}</p>`;
    }).join('');

    return out;
  },

  bindSettingsUI() {
    const modal = document.getElementById('settings-modal');
    const closeBtn = document.getElementById('btn-close-settings');
    const saveBtn = document.getElementById('btn-save-settings');
    const testBtn = document.getElementById('btn-test-connection');
    const toggleKeyBtn = document.getElementById('btn-toggle-key-visibility');
    const apiKeyInput = document.getElementById('cfg-api-key');

    if (closeBtn && modal) {
      closeBtn.addEventListener('click', () => this.closeSettingsModal());
    }

    if (toggleKeyBtn && apiKeyInput) {
      toggleKeyBtn.addEventListener('click', () => {
        const isPass = apiKeyInput.type === 'password';
        apiKeyInput.type = isPass ? 'text' : 'password';
        toggleKeyBtn.innerHTML = isPass ? Icons.eyeOff : Icons.eye;
      });
    }

    if (saveBtn) {
      saveBtn.addEventListener('click', () => {
        const baseUrl = document.getElementById('cfg-base-url').value;
        const apiKey = document.getElementById('cfg-api-key').value;
        const model = document.getElementById('cfg-model').value;
        const systemPrompt = document.getElementById('cfg-system-prompt').value;
        const fontSize = parseInt(document.getElementById('cfg-font-size').value, 10);

        window.Storage.saveConfig({ baseUrl, apiKey, model, systemPrompt, fontSize });
        this.applyEditorConfig();
        this.closeSettingsModal();
        this.showToast("Configuration saved securely.");
        window.Terminal.log(`Configuration updated: ${baseUrl} (${model})`, "system");
      });
    }

    if (testBtn) {
      testBtn.addEventListener('click', async () => {
        const testResultEl = document.getElementById('connection-test-result');
        testResultEl.className = 'test-result-box testing';
        testResultEl.textContent = "Testing endpoint connection...";

        const baseUrl = document.getElementById('cfg-base-url').value;
        const apiKey = document.getElementById('cfg-api-key').value;
        const model = document.getElementById('cfg-model').value;

        try {
          const res = await window.AIClient.testConnection({ baseUrl, apiKey, model });
          if (res.success) {
            testResultEl.className = 'test-result-box success';
            testResultEl.innerHTML = `${Icons.check} ${res.message} (${res.latency})`;
          } else {
            testResultEl.className = 'test-result-box error';
            testResultEl.innerHTML = `${Icons.alertTriangle} ${res.message}`;
          }
        } catch (err) {
          testResultEl.className = 'test-result-box error';
          testResultEl.innerHTML = `${Icons.alertTriangle} ${err.message}`;
        }
      });
    }

    // Provider presets
    document.querySelectorAll('.preset-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const url = btn.getAttribute('data-url');
        const model = btn.getAttribute('data-model');
        if (url) document.getElementById('cfg-base-url').value = url;
        if (model) document.getElementById('cfg-model').value = model;
      });
    });
  },

  openSettingsModal() {
    this.loadSettingsForm();
    const modal = document.getElementById('settings-modal');
    if (modal) modal.classList.add('active');
  },

  closeSettingsModal() {
    const modal = document.getElementById('settings-modal');
    if (modal) modal.classList.remove('active');
  },

  loadSettingsForm() {
    const cfg = window.Storage.getConfig();
    const baseUrlInput = document.getElementById('cfg-base-url');
    const apiKeyInput = document.getElementById('cfg-api-key');
    const modelInput = document.getElementById('cfg-model');
    const promptInput = document.getElementById('cfg-system-prompt');
    const fontSizeInput = document.getElementById('cfg-font-size');

    if (baseUrlInput) baseUrlInput.value = cfg.baseUrl;
    if (apiKeyInput) apiKeyInput.value = cfg.apiKey;
    if (modelInput) modelInput.value = cfg.model;
    if (promptInput) promptInput.value = cfg.systemPrompt;
    if (fontSizeInput) fontSizeInput.value = cfg.fontSize || 14;

    this.renderSettingsProvidersList();
    this.applyEditorConfig();
  },

  bindAIProvidersUI() {
    // 1. Selector in Chat Bar
    const providerSelect = document.getElementById('chat-provider-select');
    if (providerSelect) {
      providerSelect.addEventListener('change', (e) => {
        const val = e.target.value;
        if (val === '__add_new__') {
          // Reset to current active
          providerSelect.value = window.Storage.getActiveProviderId();
          this.openCustomProviderModal();
        } else {
          const provider = window.Storage.setActiveProvider(val);
          this.loadSettingsForm();
          this.renderChatProviderSelect();
          this.showToast(`Switched AI provider to ${provider.name}`);
          if (window.Terminal) {
            window.Terminal.log(`Active AI Provider switched to: ${provider.name} (${provider.model || ''})`, 'system');
          }
        }
      });
    }

    // 2. Chat Bar Buttons
    const btnAdd = document.getElementById('btn-chat-add-provider');
    if (btnAdd) {
      btnAdd.addEventListener('click', () => this.openCustomProviderModal());
    }

    const btnManage = document.getElementById('btn-chat-manage-provider');
    if (btnManage) {
      btnManage.addEventListener('click', () => {
        const active = window.Storage.getActiveProvider();
        if (active && active.isCustom) {
          this.openCustomProviderModal(active.id);
        } else {
          this.openSettingsModal();
        }
      });
    }

    const btnQuickCustom = document.getElementById('btn-chat-custom-provider-link');
    if (btnQuickCustom) {
      btnQuickCustom.addEventListener('click', () => this.openCustomProviderModal());
    }

    const btnSettingsAdd = document.getElementById('btn-settings-add-custom-provider');
    if (btnSettingsAdd) {
      btnSettingsAdd.addEventListener('click', () => {
        this.closeSettingsModal();
        this.openCustomProviderModal();
      });
    }

    // 3. Custom Provider Modal Controls
    const closeBtn = document.getElementById('btn-close-custom-provider');
    const cancelBtn = document.getElementById('btn-cancel-custom-provider');
    const saveBtn = document.getElementById('btn-save-custom-provider');
    const deleteBtn = document.getElementById('btn-delete-custom-provider');
    const testBtn = document.getElementById('btn-test-custom-provider');
    const toggleKeyBtn = document.getElementById('btn-toggle-custom-key-visibility');
    const keyInput = document.getElementById('custom-provider-key');

    if (closeBtn) closeBtn.addEventListener('click', () => this.closeCustomProviderModal());
    if (cancelBtn) cancelBtn.addEventListener('click', () => this.closeCustomProviderModal());

    if (toggleKeyBtn && keyInput) {
      toggleKeyBtn.addEventListener('click', () => {
        const isPass = keyInput.type === 'password';
        keyInput.type = isPass ? 'text' : 'password';
        toggleKeyBtn.innerHTML = isPass ? Icons.eyeOff : Icons.eye;
      });
    }

    // Quick templates buttons inside Custom Provider modal
    document.querySelectorAll('.custom-template-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const name = btn.getAttribute('data-name');
        const url = btn.getAttribute('data-url');
        const model = btn.getAttribute('data-model');
        if (name) document.getElementById('custom-provider-name').value = name;
        if (url) document.getElementById('custom-provider-base-url').value = url;
        if (model) document.getElementById('custom-provider-model').value = model;
        const keyField = document.getElementById('custom-provider-key');
        if (keyField) keyField.focus();
      });
    });

    // Test connection inside Custom Provider modal
    if (testBtn) {
      testBtn.addEventListener('click', async () => {
        const testResultEl = document.getElementById('custom-provider-test-result');
        testResultEl.className = 'test-result-box testing';
        testResultEl.textContent = 'Testing connection to provider endpoint...';

        const baseUrl = document.getElementById('custom-provider-base-url').value.trim();
        const apiKey = document.getElementById('custom-provider-key').value.trim();
        const model = document.getElementById('custom-provider-model').value.trim();
        const headersRaw = document.getElementById('custom-provider-headers').value.trim();

        let headers = null;
        if (headersRaw) {
          try {
            headers = JSON.parse(headersRaw);
          } catch (e) {
            testResultEl.className = 'test-result-box error';
            testResultEl.innerHTML = `${Icons.alertTriangle} Invalid custom headers JSON: ${e.message}`;
            return;
          }
        }

        try {
          const res = await window.AIClient.testConnection({ baseUrl, apiKey, model, headers });
          if (res.success) {
            testResultEl.className = 'test-result-box success';
            testResultEl.innerHTML = `${Icons.check} Connected successfully (${res.latency}): ${res.message}`;
          } else {
            testResultEl.className = 'test-result-box error';
            testResultEl.innerHTML = `${Icons.alertTriangle} ${res.message}`;
          }
        } catch (err) {
          testResultEl.className = 'test-result-box error';
          testResultEl.innerHTML = `${Icons.alertTriangle} ${err.message}`;
        }
      });
    }

    // Save & Activate Custom Provider
    if (saveBtn) {
      saveBtn.addEventListener('click', () => {
        const id = document.getElementById('custom-provider-id').value.trim();
        const name = document.getElementById('custom-provider-name').value.trim();
        const baseUrl = document.getElementById('custom-provider-base-url').value.trim();
        const apiKey = document.getElementById('custom-provider-key').value.trim();
        const model = document.getElementById('custom-provider-model').value.trim();
        const headersRaw = document.getElementById('custom-provider-headers').value.trim();

        if (!name) {
          this.showToast("Please provide a name for this custom provider.");
          document.getElementById('custom-provider-name').focus();
          return;
        }
        if (!baseUrl) {
          this.showToast("Please provide the API Base URL.");
          document.getElementById('custom-provider-base-url').focus();
          return;
        }

        let headers = null;
        if (headersRaw) {
          try {
            headers = JSON.parse(headersRaw);
          } catch (e) {
            this.showToast("Custom headers must be valid JSON format.");
            document.getElementById('custom-provider-headers').focus();
            return;
          }
        }

        try {
          const saved = window.Storage.saveCustomProvider({
            id: id || undefined,
            name,
            baseUrl,
            apiKey,
            model: model || 'gpt-4o',
            headers
          });

          window.Storage.setActiveProvider(saved.id);
          this.renderChatProviderSelect();
          this.renderSettingsProvidersList();
          this.loadSettingsForm();
          this.closeCustomProviderModal();
          this.showToast(`Custom provider "${saved.name}" activated!`);
          if (window.Terminal) {
            window.Terminal.log(`Added and activated custom AI provider: ${saved.name} (${saved.baseUrl})`, "system");
          }
        } catch (err) {
          this.showToast(`Failed to save: ${err.message}`);
        }
      });
    }

    // Delete Custom Provider
    if (deleteBtn) {
      deleteBtn.addEventListener('click', () => {
        const id = document.getElementById('custom-provider-id').value.trim();
        if (!id) return;

        const provider = window.Storage.getCustomProviderById(id);
        const name = provider ? provider.name : 'this provider';

        if (confirm(`Are you sure you want to remove the custom provider "${name}"?`)) {
          window.Storage.deleteCustomProvider(id);
          this.renderChatProviderSelect();
          this.renderSettingsProvidersList();
          this.loadSettingsForm();
          this.closeCustomProviderModal();
          this.showToast(`Custom provider "${name}" removed.`);
          if (window.Terminal) {
            window.Terminal.log(`Deleted custom AI provider: ${name}`, "system");
          }
        }
      });
    }
  },

  openCustomProviderModal(providerId = null) {
    const modal = document.getElementById('custom-provider-modal');
    if (!modal) return;

    const titleEl = document.getElementById('custom-provider-modal-title');
    const idInput = document.getElementById('custom-provider-id');
    const nameInput = document.getElementById('custom-provider-name');
    const urlInput = document.getElementById('custom-provider-base-url');
    const keyInput = document.getElementById('custom-provider-key');
    const modelInput = document.getElementById('custom-provider-model');
    const headersInput = document.getElementById('custom-provider-headers');
    const deleteBtn = document.getElementById('btn-delete-custom-provider');
    const testResultEl = document.getElementById('custom-provider-test-result');

    if (testResultEl) {
      testResultEl.className = 'test-result-box';
      testResultEl.textContent = '';
    }

    if (providerId) {
      const p = window.Storage.getCustomProviderById(providerId);
      if (p) {
        if (titleEl) titleEl.textContent = `Edit Custom Provider: ${p.name}`;
        if (idInput) idInput.value = p.id;
        if (nameInput) nameInput.value = p.name || '';
        if (urlInput) urlInput.value = p.baseUrl || '';
        if (keyInput) keyInput.value = p.apiKey || '';
        if (modelInput) modelInput.value = p.model || '';
        if (headersInput) headersInput.value = p.headers ? (typeof p.headers === 'object' ? JSON.stringify(p.headers, null, 2) : p.headers) : '';
        if (deleteBtn) deleteBtn.style.display = 'inline-flex';
      }
    } else {
      if (titleEl) titleEl.textContent = 'Add Custom AI Provider';
      if (idInput) idInput.value = '';
      if (nameInput) nameInput.value = '';
      if (urlInput) urlInput.value = '';
      if (keyInput) keyInput.value = '';
      if (modelInput) modelInput.value = 'gpt-4o';
      if (headersInput) headersInput.value = '';
      if (deleteBtn) deleteBtn.style.display = 'none';
    }

    modal.classList.add('active');
    this.injectIcons();
  },

  closeCustomProviderModal() {
    const modal = document.getElementById('custom-provider-modal');
    if (modal) modal.classList.remove('active');
  },

  renderChatProviderSelect() {
    const select = document.getElementById('chat-provider-select');
    if (!select) return;

    const all = window.Storage.getAllProviders();
    const activeId = window.Storage.getActiveProviderId();

    const builtins = all.filter(p => !p.isCustom);
    const customs = all.filter(p => p.isCustom);

    let html = '<optgroup label="Default Providers">';
    builtins.forEach(p => {
      const selected = p.id === activeId ? 'selected' : '';
      html += `<option value="${p.id}" ${selected}>${p.name} (${p.model || 'default'})</option>`;
    });
    html += '</optgroup>';

    if (customs.length > 0) {
      html += '<optgroup label="Custom Providers">';
      customs.forEach(p => {
        const selected = p.id === activeId ? 'selected' : '';
        html += `<option value="${p.id}" ${selected}>${p.name} (${p.model || 'custom'})</option>`;
      });
      html += '</optgroup>';
    }

    html += '<optgroup label="Options">';
    html += '<option value="__add_new__">+ Add Custom Provider...</option>';
    html += '</optgroup>';

    select.innerHTML = html;
  },

  renderSettingsProvidersList() {
    const container = document.getElementById('settings-providers-list');
    if (!container) return;

    const all = window.Storage.getAllProviders();
    const activeId = window.Storage.getActiveProviderId();

    let html = '';
    all.forEach(p => {
      const isActive = p.id === activeId;
      const activeClass = isActive ? 'active' : '';
      const badgeHtml = isActive 
        ? '<span class="provider-item-badge badge-active">Active</span>'
        : (p.isCustom 
          ? '<span class="provider-item-badge badge-custom">Custom</span>'
          : '<span class="provider-item-badge badge-builtin">Preset</span>');

      html += `
        <div class="provider-item-card ${activeClass}" data-id="${p.id}">
          <div class="provider-item-left" data-action="activate" data-id="${p.id}">
            <div class="provider-item-title-row">
              <span class="provider-item-name">${this.escapeHtml(p.name)}</span>
              ${badgeHtml}
            </div>
            <div class="provider-item-meta">${this.escapeHtml(p.baseUrl)} &bull; ${this.escapeHtml(p.model || 'default')}</div>
          </div>
          <div class="provider-item-actions">
            ${p.isCustom ? `
              <button type="button" class="btn btn-icon btn-sm" data-action="edit-custom" data-id="${p.id}" title="Edit Provider">
                <span class="btn-icon-svg" data-icon="edit"></span>
              </button>
              <button type="button" class="btn btn-icon btn-sm" data-action="delete-custom" data-id="${p.id}" title="Delete Provider" style="color:var(--accent-red);">
                <span class="btn-icon-svg" data-icon="trash"></span>
              </button>
            ` : (isActive ? '' : `
              <button type="button" class="btn btn-xs btn-outline" data-action="activate" data-id="${p.id}">
                Use
              </button>
            `)}
          </div>
        </div>
      `;
    });

    container.innerHTML = html;
    this.injectIcons();

    // Attach listeners
    container.querySelectorAll('[data-action="activate"]').forEach(el => {
      el.addEventListener('click', () => {
        const id = el.getAttribute('data-id');
        if (id && id !== window.Storage.getActiveProviderId()) {
          const prov = window.Storage.setActiveProvider(id);
          this.renderChatProviderSelect();
          this.renderSettingsProvidersList();
          this.loadSettingsForm();
          this.showToast(`Switched active provider to ${prov.name}`);
        }
      });
    });

    container.querySelectorAll('[data-action="edit-custom"]').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const id = btn.getAttribute('data-id');
        this.closeSettingsModal();
        this.openCustomProviderModal(id);
      });
    });

    container.querySelectorAll('[data-action="delete-custom"]').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const id = btn.getAttribute('data-id');
        const provider = window.Storage.getCustomProviderById(id);
        const name = provider ? provider.name : 'this provider';
        if (confirm(`Delete custom provider "${name}"?`)) {
          window.Storage.deleteCustomProvider(id);
          this.renderChatProviderSelect();
          this.renderSettingsProvidersList();
          this.loadSettingsForm();
          this.showToast(`Deleted "${name}".`);
        }
      });
    });
  },

  applyEditorConfig() {
    const cfg = window.Storage.getConfig();
    const textarea = document.getElementById('editor-textarea');
    const highlight = document.getElementById('editor-highlight');
    const lineNums = document.getElementById('editor-line-numbers');
    const size = (cfg.fontSize || 14) + 'px';

    if (textarea) textarea.style.fontSize = size;
    if (highlight) highlight.style.fontSize = size;
    if (lineNums) lineNums.style.fontSize = size;
  },

  bindViewportAndKeyboard() {
    if (window.visualViewport) {
      const resizeHandler = () => {
        const vh = window.visualViewport.height;
        document.documentElement.style.setProperty('--viewport-height', `${vh}px`);
      };
      window.visualViewport.addEventListener('resize', resizeHandler);
      window.visualViewport.addEventListener('scroll', resizeHandler);
      resizeHandler();
    }
  },

  onBackPress() {
    const runnerModal = document.getElementById('runner-modal');
    if (runnerModal && runnerModal.classList.contains('active')) {
      if (window.Runner) window.Runner.close();
      return true;
    }
    const termuxModal = document.getElementById('termux-runtime-modal');
    if (termuxModal && termuxModal.classList.contains('active')) {
      termuxModal.classList.remove('active');
      return true;
    }
    const apkModal = document.getElementById('apk-modal');
    if (apkModal && apkModal.classList.contains('active')) {
      apkModal.classList.remove('active');
      return true;
    }
    const customModal = document.getElementById('custom-provider-modal');
    if (customModal && customModal.classList.contains('active')) {
      this.closeCustomProviderModal();
      return true;
    }
    const modal = document.getElementById('settings-modal');
    if (modal && modal.classList.contains('active')) {
      this.closeSettingsModal();
      return true;
    }
    const searchBar = document.getElementById('editor-search-bar');
    if (searchBar && searchBar.classList.contains('active')) {
      window.Editor.toggleSearchBar(false);
      return true;
    }
    if (window.innerWidth < 768 && this.currentView !== 'editor') {
      this.switchView('editor');
      return true;
    }
    return false;
  },

  showToast(msg) {
    let toast = document.getElementById('app-toast');
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'app-toast';
      toast.className = 'app-toast';
      document.body.appendChild(toast);
    }
    toast.textContent = msg;
    toast.classList.add('visible');
    setTimeout(() => { toast.classList.remove('visible'); }, 2600);
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

window.App = App;
window.handleAndroidBackPress = function() {
  if (window.App && typeof window.App.onBackPress === 'function') {
    return window.App.onBackPress();
  }
  return false;
};
window.addEventListener('DOMContentLoaded', () => {
  App.init();
});
