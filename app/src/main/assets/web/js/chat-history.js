// Codex Mobile - Persistent Local AI Chat History (Backed by Room DB)
const ChatHistory = {
  currentConversationId: null,
  conversations: [],
  isOpen: false,

  init() {
    this.createHistoryDrawerDOM();
    this.bindEvents();
    this.loadConversations();
  },

  createHistoryDrawerDOM() {
    if (document.getElementById('chat-history-drawer')) return;

    const drawer = document.createElement('div');
    drawer.id = 'chat-history-drawer';
    drawer.className = 'history-drawer-overlay';
    drawer.innerHTML = `
      <div class="history-drawer-panel">
        <!-- Drawer Header -->
        <div class="history-drawer-header">
          <div style="display:flex; align-items:center; gap:8px;">
            <span class="btn-icon-svg">${Icons.history}</span>
            <span>Saved Chat Sessions</span>
          </div>
          <button type="button" class="btn btn-icon btn-sm" id="history-close-btn" title="Close History">
            <span class="btn-icon-svg">${Icons.close}</span>
          </button>
        </div>

        <!-- Search and New Chat Actions -->
        <div class="history-actions-bar">
          <div class="history-search-wrapper">
            <span class="btn-icon-svg">${Icons.search}</span>
            <input type="text" id="history-search-input" class="history-search-input" placeholder="Search conversations...">
          </div>
          <button type="button" class="btn btn-sm btn-primary" id="history-new-chat-btn" title="Start fresh conversation">
            <span class="btn-icon-svg">${Icons.plus}</span>
            <span>New Chat</span>
          </button>
        </div>

        <!-- Conversations List -->
        <div class="history-list-container" id="history-list-container">
          <div class="history-empty">Loading chat history...</div>
        </div>

        <!-- Footer Info -->
        <div class="history-footer">
          <span class="btn-icon-svg">${Icons.lock}</span>
          <span>100% on-device SQLite / Room database. Never leaves your phone.</span>
        </div>
      </div>
    `;

    document.body.appendChild(drawer);
  },

  bindEvents() {
    const drawer = document.getElementById('chat-history-drawer');
    const closeBtn = document.getElementById('history-close-btn');
    const newChatBtn = document.getElementById('history-new-chat-btn');
    const searchInput = document.getElementById('history-search-input');

    if (closeBtn) closeBtn.addEventListener('click', () => this.close());
    if (drawer) {
      drawer.addEventListener('click', (e) => {
        if (e.target === drawer) this.close();
      });
    }

    if (newChatBtn) {
      newChatBtn.addEventListener('click', () => {
        this.startNewConversation();
        this.close();
      });
    }

    if (searchInput) {
      let debounceTimer = null;
      searchInput.addEventListener('input', (e) => {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => {
          const query = e.target.value.trim();
          this.searchConversations(query);
        }, 200);
      });
    }
  },

  open() {
    const drawer = document.getElementById('chat-history-drawer');
    if (drawer) drawer.classList.add('active');
    this.isOpen = true;
    this.loadConversations();
  },

  close() {
    const drawer = document.getElementById('chat-history-drawer');
    if (drawer) drawer.classList.remove('active');
    this.isOpen = false;
  },

  async loadConversations() {
    if (window.Bridge && window.Bridge.isAvailable()) {
      try {
        const convos = await window.Bridge.getChatConversations();
        this.conversations = convos || [];
        this.renderConversationList();
      } catch (err) {
        console.warn("Could not load conversations from native DB:", err);
        this.loadWebFallbackConversations();
      }
    } else {
      this.loadWebFallbackConversations();
    }
  },

  async searchConversations(query) {
    if (!query) {
      this.loadConversations();
      return;
    }
    if (window.Bridge && window.Bridge.isAvailable()) {
      try {
        const convos = await window.Bridge.searchChatConversations(query);
        this.conversations = convos || [];
        this.renderConversationList();
      } catch (err) {
        this.filterLocalConversations(query);
      }
    } else {
      this.filterLocalConversations(query);
    }
  },

  filterLocalConversations(query) {
    const q = query.toLowerCase();
    const filtered = this.conversations.filter(c =>
      (c.title && c.title.toLowerCase().includes(q)) ||
      (c.projectName && c.projectName.toLowerCase().includes(q))
    );
    this.renderConversationList(filtered);
  },

  loadWebFallbackConversations() {
    try {
      const stored = localStorage.getItem('codex_web_chat_history');
      this.conversations = stored ? JSON.parse(stored) : [];
    } catch (e) {
      this.conversations = [];
    }
    this.renderConversationList();
  },

  renderConversationList(listToRender = null) {
    const list = listToRender !== null ? listToRender : this.conversations;
    const container = document.getElementById('history-list-container');
    if (!container) return;

    container.innerHTML = '';

    if (!list || list.length === 0) {
      container.innerHTML = `<div class="history-empty">No conversations found. Tap "New Chat" to begin.</div>`;
      return;
    }

    for (const c of list) {
      const item = document.createElement('div');
      item.className = `history-item ${c.id === this.currentConversationId ? 'active' : ''}`;

      const dateStr = this.formatDate(c.updatedAt || c.createdAt);
      const projTag = c.projectName ? `<span class="history-tag">${c.projectName}</span>` : '';

      item.innerHTML = `
        <div class="history-item-body">
          <div class="history-item-title-row">
            <span class="history-item-title" title="${c.title}">${this.escapeHtml(c.title || 'Conversation')}</span>
          </div>
          <div class="history-item-meta">
            <span>${dateStr}</span>
            ${projTag}
            <span class="history-model">${c.model || 'gpt-4o'}</span>
          </div>
        </div>
        <div class="history-item-actions">
          <button type="button" class="btn btn-icon btn-sm btn-edit-title" title="Rename Session">
            <span class="btn-icon-svg">${Icons.edit}</span>
          </button>
          <button type="button" class="btn btn-icon btn-sm btn-delete-convo" title="Delete Conversation">
            <span class="btn-icon-svg">${Icons.trash}</span>
          </button>
        </div>
      `;

      // Switch to conversation
      item.querySelector('.history-item-body').addEventListener('click', () => {
        this.switchToConversation(c.id);
        this.close();
      });

      // Edit title
      item.querySelector('.btn-edit-title').addEventListener('click', (e) => {
        e.stopPropagation();
        const newTitle = prompt("Edit conversation title:", c.title);
        if (newTitle && newTitle.trim() && newTitle.trim() !== c.title) {
          this.renameConversation(c.id, newTitle.trim());
        }
      });

      // Delete conversation
      item.querySelector('.btn-delete-convo').addEventListener('click', (e) => {
        e.stopPropagation();
        if (confirm(`Delete conversation "${c.title}"?`)) {
          this.deleteConversation(c.id);
        }
      });

      container.appendChild(item);
    }
  },

  async startNewConversation(initialTitle = "New Conversation") {
    const projName = window.FileSystem && window.FileSystem.currentProject ? window.FileSystem.currentProject.name : "";
    const projUri = window.FileSystem && window.FileSystem.currentProject ? window.FileSystem.currentProject.rootUri : "";
    const config = window.Storage ? window.Storage.getConfig() : {};
    const model = config.model || "gpt-4o";

    if (window.Bridge && window.Bridge.isAvailable()) {
      try {
        const res = await window.Bridge.createChatConversation(initialTitle, projUri || "", projName || "", model);
        this.currentConversationId = res.id;
      } catch (e) {
        this.currentConversationId = 'web_' + Date.now();
      }
    } else {
      this.currentConversationId = 'web_' + Date.now();
      const newConvo = {
        id: this.currentConversationId,
        title: initialTitle,
        createdAt: Date.now(),
        updatedAt: Date.now(),
        projectName: projName,
        model: model,
        messages: []
      };
      this.conversations.unshift(newConvo);
      try {
        localStorage.setItem('codex_web_chat_history', JSON.stringify(this.conversations));
      } catch (e) {}
    }

    // Reset AIClient in-memory history & UI
    if (window.AIClient) window.AIClient.clearHistory();
    const msgList = document.getElementById('chat-messages-list');
    if (msgList) msgList.innerHTML = '';
    if (window.App) window.App.appendAssistantGreeting();

    if (window.Terminal) {
      window.Terminal.log(`Started new AI chat session (${this.currentConversationId})`, "system");
    }
  },

  async switchToConversation(convoId) {
    this.currentConversationId = convoId;
    const msgList = document.getElementById('chat-messages-list');
    if (!msgList) return;

    msgList.innerHTML = '<div class="chat-loading-notice">Loading conversation messages...</div>';

    if (window.Bridge && window.Bridge.isAvailable()) {
      try {
        const messages = await window.Bridge.getChatMessages(convoId);
        this.renderLoadedMessages(messages);
      } catch (err) {
        msgList.innerHTML = `<div class="ai-error-box">Failed to load conversation: ${err.message}</div>`;
      }
    } else {
      const convo = this.conversations.find(c => c.id === convoId);
      const messages = convo && convo.messages ? convo.messages : [];
      this.renderLoadedMessages(messages);
    }
  },

  renderLoadedMessages(messages) {
    const msgList = document.getElementById('chat-messages-list');
    if (!msgList) return;
    msgList.innerHTML = '';

    if (!messages || messages.length === 0) {
      if (window.App) window.App.appendAssistantGreeting();
      return;
    }

    // Populate AIClient in-memory history
    if (window.AIClient) {
      window.AIClient.conversationHistory = [];
      for (const m of messages) {
        window.AIClient.conversationHistory.push({ role: m.role, content: m.content });
      }
    }

    for (const m of messages) {
      const isUser = m.role === 'user';
      const msgEl = document.createElement('div');
      msgEl.className = `chat-message message-${isUser ? 'user' : 'assistant'}`;

      const timeStr = m.timestamp ? new Date(m.timestamp).toTimeString().split(' ')[0] : '';
      const author = isUser ? 'You' : `${Icons.sparkles} Codex AI`;

      msgEl.innerHTML = `
        <div class="message-header">
          <span class="message-author">${author}</span>
          <span class="message-time">${timeStr}</span>
        </div>
        <div class="message-body">${isUser ? this.escapeHtml(m.content) : (window.App ? window.App.renderMarkdown(m.content) : m.content)}</div>
      `;

      if (!isUser && m.proposalsJson) {
        try {
          const proposals = JSON.parse(m.proposalsJson);
          if (proposals.length > 0 && window.DiffPatch) {
            const previewContainer = window.DiffPatch.renderChangePreview(
              proposals,
              async (propsToApply) => {
                const result = await window.FileSystem.applyChangeProposals(propsToApply);
                window.App.showToast(`Applied ${result.applied.length} changes`);
              },
              () => window.App.showToast("Changes dismissed")
            );
            msgEl.querySelector('.message-body').appendChild(previewContainer);
          }
        } catch (e) {}
      }

      msgList.appendChild(msgEl);
    }

    msgList.scrollTop = msgList.scrollHeight;
  },

  async persistMessage(role, content, proposals = null) {
    if (!this.currentConversationId) {
      const summaryTitle = content.length > 35 ? content.substring(0, 35) + '...' : content;
      await this.startNewConversation(summaryTitle);
    }

    const proposalsJson = proposals && proposals.length > 0 ? JSON.stringify(proposals) : "";

    if (window.Bridge && window.Bridge.isAvailable()) {
      try {
        await window.Bridge.saveChatMessage(this.currentConversationId, role, content, proposalsJson, false);
      } catch (e) {
        console.warn("Failed to persist message to Room DB:", e);
      }
    } else {
      // Local fallback
      const convo = this.conversations.find(c => c.id === this.currentConversationId);
      if (convo) {
        if (!convo.messages) convo.messages = [];
        convo.messages.push({
          id: 'msg_' + Date.now(),
          role,
          content,
          timestamp: Date.now(),
          proposalsJson
        });
        convo.updatedAt = Date.now();
        try {
          localStorage.setItem('codex_web_chat_history', JSON.stringify(this.conversations));
        } catch (e) {}
      }
    }
  },

  async renameConversation(convoId, newTitle) {
    if (window.Bridge && window.Bridge.isAvailable()) {
      try {
        await window.Bridge.updateChatTitle(convoId, newTitle);
        this.loadConversations();
      } catch (e) {
        alert("Rename failed: " + e.message);
      }
    } else {
      const convo = this.conversations.find(c => c.id === convoId);
      if (convo) {
        convo.title = newTitle;
        try {
          localStorage.setItem('codex_web_chat_history', JSON.stringify(this.conversations));
        } catch (e) {}
        this.renderConversationList();
      }
    }
  },

  async deleteConversation(convoId) {
    if (window.Bridge && window.Bridge.isAvailable()) {
      try {
        await window.Bridge.deleteChatConversation(convoId);
        if (this.currentConversationId === convoId) {
          this.startNewConversation();
        }
        this.loadConversations();
      } catch (e) {
        alert("Delete failed: " + e.message);
      }
    } else {
      this.conversations = this.conversations.filter(c => c.id !== convoId);
      try {
        localStorage.setItem('codex_web_chat_history', JSON.stringify(this.conversations));
      } catch (e) {}
      if (this.currentConversationId === convoId) {
        this.startNewConversation();
      }
      this.renderConversationList();
    }
  },

  formatDate(timestamp) {
    if (!timestamp) return 'Recently';
    const date = new Date(timestamp);
    const now = new Date();
    const diffHours = (now - date) / (1000 * 60 * 60);

    if (diffHours < 24 && date.getDate() === now.getDate()) {
      return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }
    return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
  },

  escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
};

window.ChatHistory = ChatHistory;
