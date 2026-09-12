// Codex Mobile - Background AI Task & Sync Coordinator
const BackgroundTasks = {
  activeTasks: [],
  pollTimer: null,

  init() {
    this.createTaskBannerDOM();
    this.startPolling();
  },

  createTaskBannerDOM() {
    if (document.getElementById('bg-task-floating-banner')) return;

    const banner = document.createElement('div');
    banner.id = 'bg-task-floating-banner';
    banner.className = 'bg-task-floating-banner';
    banner.style.display = 'none';
    banner.innerHTML = `
      <div class="bg-task-content">
        <div class="bg-task-spinner"></div>
        <div class="bg-task-info">
          <div class="bg-task-title" id="bg-task-title">AI Background Task</div>
          <div class="bg-task-status" id="bg-task-status">Running in Android Foreground Service...</div>
        </div>
        <div class="bg-task-actions">
          <button type="button" class="btn btn-sm btn-icon" id="bg-task-dismiss-btn" title="Dismiss / Cancel">
            <span class="btn-icon-svg">${Icons.close}</span>
          </button>
        </div>
      </div>
    `;

    document.body.appendChild(banner);

    const dismissBtn = document.getElementById('bg-task-dismiss-btn');
    if (dismissBtn) {
      dismissBtn.addEventListener('click', () => {
        if (this.activeTasks.length > 0) {
          const t = this.activeTasks[0];
          this.dismissTask(t.id);
        }
      });
    }
  },

  async startTask(params) {
    if (!window.Bridge || !window.Bridge.isAvailable()) {
      // Direct in-process fallback
      return null;
    }

    try {
      const task = await window.Bridge.startBackgroundAiTask(params);
      this.activeTasks.push(task);
      this.updateBanner();
      if (window.Terminal) {
        window.Terminal.log(`Started background AI task: ${task.promptSnippet}`, "api");
      }
      return task;
    } catch (e) {
      console.warn("Could not start background task:", e);
      return null;
    }
  },

  startPolling() {
    if (this.pollTimer) clearInterval(this.pollTimer);

    this.pollTimer = setInterval(async () => {
      if (!window.Bridge || !window.Bridge.isAvailable()) return;

      try {
        const tasks = await window.Bridge.getBackgroundTasks();
        this.syncTasks(tasks || []);
      } catch (e) {}
    }, 2500);
  },

  syncTasks(tasks) {
    this.activeTasks = tasks;
    this.updateBanner();

    // Check for newly completed tasks that need syncing into UI
    for (const t of tasks) {
      if (t.status === 'COMPLETED' && !t.hasSynced) {
        t.hasSynced = true;
        this.onTaskCompleted(t);
      }
    }
  },

  updateBanner() {
    const banner = document.getElementById('bg-task-floating-banner');
    if (!banner) return;

    const runningTask = this.activeTasks.find(t => t.status === 'RUNNING' || t.status === 'QUEUED');

    if (runningTask) {
      banner.style.display = 'block';
      document.getElementById('bg-task-title').textContent = runningTask.promptSnippet || "AI Generation Task";
      document.getElementById('bg-task-status').textContent = "Running in Android Foreground Service...";
    } else {
      banner.style.display = 'none';
    }
  },

  async dismissTask(taskId) {
    if (window.Bridge && window.Bridge.isAvailable()) {
      try {
        await window.Bridge.dismissBackgroundTask(taskId);
      } catch (e) {}
    }
    this.activeTasks = this.activeTasks.filter(t => t.id !== taskId);
    this.updateBanner();
  },

  onTaskCompleted(task) {
    if (window.Terminal) {
      window.Terminal.log(`Background AI task completed (${task.id})`, "api");
    }

    if (window.App && window.App.showToast) {
      window.App.showToast("Background AI task finished! Updating chat...");
    }

    // If active conversation matches, insert result
    if (window.ChatHistory && window.ChatHistory.currentConversationId === task.conversationId) {
      const msgList = document.getElementById('chat-messages-list');
      if (msgList) {
        // Render assistant response
        const assistantMsgEl = document.createElement('div');
        assistantMsgEl.className = 'chat-message message-assistant';
        assistantMsgEl.innerHTML = `
          <div class="message-header">
            <span class="message-author">${Icons.sparkles} Codex AI (Background)</span>
            <span class="message-time">${new Date(task.updatedAt).toTimeString().split(' ')[0]}</span>
          </div>
          <div class="message-body">${window.App ? window.App.renderMarkdown(task.resultText) : task.resultText}</div>
        `;

        const proposals = window.DiffPatch ? window.DiffPatch.extractProposals(task.resultText) : [];
        if (proposals.length > 0 && window.DiffPatch) {
          const previewContainer = window.DiffPatch.renderChangePreview(
            proposals,
            async (propsToApply) => {
              const result = await window.FileSystem.applyChangeProposals(propsToApply);
              window.App.showToast(`Applied ${result.applied.length} changes`);
            },
            () => window.App.showToast("Changes dismissed")
          );
          assistantMsgEl.querySelector('.message-body').appendChild(previewContainer);
        }

        msgList.appendChild(assistantMsgEl);
        msgList.scrollTop = msgList.scrollHeight;
      }
    }
  }
};

window.BackgroundTasks = BackgroundTasks;
