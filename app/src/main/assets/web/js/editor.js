// Codex Mobile - Professional Touch-Optimized Code Editor
const Editor = {
  openTabs: [], // array of { path, name, isDirty, content, history: [] }
  activeTabPath: null,
  container: null,
  textarea: null,
  lineNumbersEl: null,
  highlightEl: null,
  touchToolbarEl: null,
  searchBarEl: null,
  isComposing: false,
  undoStack: {}, // path -> array of strings
  redoStack: {}, // path -> array of strings

  init() {
    this.container = document.getElementById('editor-container');
    this.textarea = document.getElementById('editor-textarea');
    this.lineNumbersEl = document.getElementById('editor-line-numbers');
    this.highlightEl = document.getElementById('editor-highlight');
    this.touchToolbarEl = document.getElementById('editor-touch-toolbar');
    this.searchBarEl = document.getElementById('editor-search-bar');

    this.bindEvents();
    this.buildTouchToolbar();
  },

  bindEvents() {
    if (!this.textarea) return;

    // Sync scrolling between textarea, line numbers, and syntax highlight layer
    this.textarea.addEventListener('scroll', () => {
      if (this.lineNumbersEl) this.lineNumbersEl.scrollTop = this.textarea.scrollTop;
      if (this.highlightEl) {
        this.highlightEl.scrollTop = this.textarea.scrollTop;
        this.highlightEl.scrollLeft = this.textarea.scrollLeft;
      }
    });

    // Content input and dirty tracking
    this.textarea.addEventListener('input', () => {
      this.handleInput();
    });

    // Keydown shortcuts (Tab, Save Ctrl/Cmd+S, Undo/Redo)
    this.textarea.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        this.saveCurrentFile();
        return;
      }

      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') {
        e.preventDefault();
        this.toggleSearchBar();
        return;
      }

      if (e.key === 'Tab') {
        e.preventDefault();
        this.insertText('  ');
        return;
      }

      // Auto-closing brackets and quotes
      const pairs = { '(': ')', '[': ']', '{': '}', '"': '"', "'": "'", '`': '`' };
      if (pairs[e.key]) {
        const start = this.textarea.selectionStart;
        const end = this.textarea.selectionEnd;
        const val = this.textarea.value;
        if (start !== end) {
          e.preventDefault();
          const selected = val.substring(start, end);
          const replacement = e.key + selected + pairs[e.key];
          this.textarea.setRangeText(replacement, start, end, 'select');
          this.handleInput();
        }
      }
    });

    // Search bar events
    const findInput = document.getElementById('search-find-input');
    const replaceInput = document.getElementById('search-replace-input');
    const findNextBtn = document.getElementById('search-find-next');
    const replaceBtn = document.getElementById('search-replace-btn');
    const replaceAllBtn = document.getElementById('search-replace-all-btn');
    const closeSearchBtn = document.getElementById('search-close-btn');

    if (findInput) {
      findInput.addEventListener('input', () => this.highlightMatches(findInput.value));
      findInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') this.findNext(findInput.value);
      });
    }
    if (findNextBtn) findNextBtn.addEventListener('click', () => this.findNext(findInput.value));
    if (replaceBtn) replaceBtn.addEventListener('click', () => this.replaceCurrent(findInput.value, replaceInput.value));
    if (replaceAllBtn) replaceAllBtn.addEventListener('click', () => this.replaceAll(findInput.value, replaceInput.value));
    if (closeSearchBtn) closeSearchBtn.addEventListener('click', () => this.toggleSearchBar(false));
  },

  buildTouchToolbar() {
    if (!this.touchToolbarEl) return;
    const symbols = [
      { label: 'Tab', action: () => this.insertText('  ') },
      { label: '{ }', action: () => this.insertWrap('{', '}') },
      { label: '( )', action: () => this.insertWrap('(', ')') },
      { label: '[ ]', action: () => this.insertWrap('[', ']') },
      { label: '< >', action: () => this.insertWrap('<', '>') },
      { label: '=', action: () => this.insertText('=') },
      { label: ';', action: () => this.insertText(';') },
      { label: ':', action: () => this.insertText(':') },
      { label: '"', action: () => this.insertWrap('"', '"') },
      { label: "'", action: () => this.insertWrap("'", "'") },
      { label: '`', action: () => this.insertWrap('`', '`') },
      { label: '->', action: () => this.insertText('->') },
      { label: '=>', action: () => this.insertText('=>') },
      { label: '$', action: () => this.insertText('$') },
      { label: '.', action: () => this.insertText('.') },
      { label: ',', action: () => this.insertText(',') },
      { label: '/', action: () => this.insertText('/') },
      { label: '+', action: () => this.insertText('+') },
      { label: '-', action: () => this.insertText('-') },
      { label: '*', action: () => this.insertText('*') },
      { label: '!', action: () => this.insertText('!') },
      { label: '|', action: () => this.insertText('|') },
      { label: '&', action: () => this.insertText('&') },
      { label: 'Undo', action: () => this.undo() },
      { label: 'Redo', action: () => this.redo() }
    ];

    this.touchToolbarEl.innerHTML = '';
    for (const sym of symbols) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'touch-toolbar-btn';
      btn.textContent = sym.label;
      btn.addEventListener('mousedown', (e) => e.preventDefault()); // preserve focus
      btn.addEventListener('click', (e) => {
        e.preventDefault();
        sym.action();
        this.textarea.focus();
      });
      this.touchToolbarEl.appendChild(btn);
    }
  },

  insertText(str) {
    if (!this.textarea) return;
    const start = this.textarea.selectionStart;
    const end = this.textarea.selectionEnd;
    this.textarea.setRangeText(str, start, end, 'end');
    this.handleInput();
  },

  insertWrap(before, after) {
    if (!this.textarea) return;
    const start = this.textarea.selectionStart;
    const end = this.textarea.selectionEnd;
    const val = this.textarea.value;
    const selected = val.substring(start, end);
    const replacement = before + selected + after;
    this.textarea.setRangeText(replacement, start, end, 'select');
    if (start === end) {
      // place cursor in the middle
      this.textarea.selectionStart = start + before.length;
      this.textarea.selectionEnd = start + before.length;
    }
    this.handleInput();
  },

  handleInput() {
    const val = this.textarea.value;
    const activeTab = this.openTabs.find(t => t.path === this.activeTabPath);
    if (activeTab) {
      if (!activeTab.isDirty) {
        activeTab.isDirty = true;
        this.renderTabs();
      }
      activeTab.content = val;

      // Push history occasionally
      if (!this.undoStack[activeTab.path]) this.undoStack[activeTab.path] = [];
      const stack = this.undoStack[activeTab.path];
      if (stack.length === 0 || stack[stack.length - 1] !== val) {
        stack.push(val);
        if (stack.length > 50) stack.shift();
      }
    }

    this.updateLineNumbers();
    this.updateHighlight();
  },

  undo() {
    if (!this.activeTabPath) return;
    const stack = this.undoStack[this.activeTabPath];
    if (stack && stack.length > 1) {
      if (!this.redoStack[this.activeTabPath]) this.redoStack[this.activeTabPath] = [];
      this.redoStack[this.activeTabPath].push(stack.pop());
      const prev = stack[stack.length - 1];
      this.textarea.value = prev;
      this.handleInput();
    }
  },

  redo() {
    if (!this.activeTabPath) return;
    const stack = this.redoStack[this.activeTabPath];
    if (stack && stack.length > 0) {
      const next = stack.pop();
      this.textarea.value = next;
      this.handleInput();
    }
  },

  loadFile(path, content) {
    let tab = this.openTabs.find(t => t.path === path);
    if (!tab) {
      tab = {
        path,
        name: path.split('/').pop(),
        content: content || "",
        isDirty: false
      };
      this.openTabs.push(tab);
    } else {
      tab.content = content || "";
      tab.isDirty = false;
    }

    this.activeTabPath = path;
    if (this.textarea) {
      this.textarea.value = tab.content;
      this.textarea.scrollTop = 0;
      this.textarea.scrollLeft = 0;
    }

    if (!this.undoStack[path]) this.undoStack[path] = [tab.content];

    this.renderTabs();
    this.updateLineNumbers();
    this.updateHighlight();
  },

  renderTabs() {
    const tabsContainer = document.getElementById('editor-tabs-bar');
    if (!tabsContainer) return;

    tabsContainer.innerHTML = '';
    for (const tab of this.openTabs) {
      const tabEl = document.createElement('div');
      tabEl.className = `editor-tab ${tab.path === this.activeTabPath ? 'active' : ''}`;
      
      const titleSpan = document.createElement('span');
      titleSpan.className = 'tab-title';
      titleSpan.textContent = tab.name;
      tabEl.appendChild(titleSpan);

      if (tab.isDirty) {
        const dirtyDot = document.createElement('span');
        dirtyDot.className = 'tab-dirty-indicator';
        tabEl.appendChild(dirtyDot);
      }

      const closeBtn = document.createElement('button');
      closeBtn.type = 'button';
      closeBtn.className = 'tab-close-btn';
      closeBtn.innerHTML = Icons.close;
      closeBtn.setAttribute('aria-label', `Close ${tab.name}`);
      closeBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        this.closeTab(tab.path);
      });
      tabEl.appendChild(closeBtn);

      tabEl.addEventListener('click', () => {
        if (this.activeTabPath !== tab.path) {
          this.switchTab(tab.path);
        }
      });

      tabsContainer.appendChild(tabEl);
    }
  },

  switchTab(path) {
    const tab = this.openTabs.find(t => t.path === path);
    if (!tab) return;
    this.activeTabPath = path;
    if (this.textarea) {
      this.textarea.value = tab.content;
    }
    this.renderTabs();
    this.updateLineNumbers();
    this.updateHighlight();
    if (window.FileSystem) {
      window.FileSystem.activeFilePath = path;
      window.FileSystem.notify();
    }
  },

  closeTab(path) {
    const index = this.openTabs.findIndex(t => t.path === path);
    if (index === -1) return;

    const tab = this.openTabs[index];
    if (tab.isDirty) {
      if (!confirm(`"${tab.name}" has unsaved changes. Close anyway?`)) {
        return;
      }
    }

    this.openTabs.splice(index, 1);
    if (this.activeTabPath === path) {
      if (this.openTabs.length > 0) {
        const nextTab = this.openTabs[Math.max(0, index - 1)];
        this.switchTab(nextTab.path);
      } else {
        this.activeTabPath = null;
        if (this.textarea) this.textarea.value = '';
        this.renderTabs();
        this.updateLineNumbers();
        this.updateHighlight();
      }
    } else {
      this.renderTabs();
    }
  },

  updateLineNumbers() {
    if (!this.lineNumbersEl || !this.textarea) return;
    const lines = this.textarea.value.split('\n');
    const lineCount = lines.length;
    let html = '';
    for (let i = 1; i <= lineCount; i++) {
      html += `<div class="line-num">${i}</div>`;
    }
    this.lineNumbersEl.innerHTML = html;
  },

  updateHighlight() {
    if (!this.highlightEl || !this.textarea || !this.activeTabPath) return;
    const code = this.textarea.value;
    const lang = window.FileSystem ? window.FileSystem.getLanguage(this.activeTabPath) : 'text';
    this.highlightEl.innerHTML = this.highlightCode(code, lang) + '\n';
  },

  // Zero-dependency, performant regex-based syntax token highlighter
  highlightCode(code, lang) {
    if (!code) return '';
    // HTML sanitize first
    let escaped = code
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');

    // Keywords dictionary
    const keywords = {
      javascript: ['const', 'let', 'var', 'function', 'return', 'if', 'else', 'for', 'while', 'switch', 'case', 'break', 'continue', 'new', 'class', 'import', 'export', 'default', 'from', 'async', 'await', 'try', 'catch', 'throw', 'typeof', 'instanceof', 'this', 'true', 'false', 'null', 'undefined'],
      typescript: ['const', 'let', 'var', 'function', 'return', 'if', 'else', 'for', 'while', 'new', 'class', 'interface', 'type', 'import', 'export', 'async', 'await', 'this', 'true', 'false', 'null', 'undefined', 'string', 'number', 'boolean', 'any'],
      python: ['def', 'class', 'return', 'if', 'elif', 'else', 'for', 'while', 'import', 'from', 'as', 'try', 'except', 'finally', 'raise', 'with', 'in', 'is', 'not', 'and', 'or', 'True', 'False', 'None', 'self', 'lambda', 'pass', 'async', 'await'],
      kotlin: ['val', 'var', 'fun', 'class', 'object', 'interface', 'return', 'if', 'else', 'for', 'while', 'when', 'import', 'package', 'public', 'private', 'override', 'null', 'true', 'false', 'this', 'super', 'is', 'in', 'suspend', 'sealed'],
      java: ['public', 'private', 'protected', 'class', 'interface', 'extends', 'implements', 'void', 'int', 'boolean', 'String', 'return', 'if', 'else', 'for', 'while', 'new', 'import', 'package', 'this', 'super', 'null', 'true', 'false'],
      php: ['function', 'return', 'if', 'else', 'elseif', 'for', 'foreach', 'while', 'class', 'public', 'private', 'protected', 'new', 'echo', 'true', 'false', 'null']
    };

    const kwList = keywords[lang] || keywords.javascript;
    const kwRegex = new RegExp(`\\b(${kwList.join('|')})\\b`, 'g');

    // Comment and string preservation tokens
    const tokens = [];
    let tokenIndex = 0;
    const saveToken = (cls, text) => {
      const placeholder = `___TOK_${tokenIndex++}___`;
      tokens.push({ placeholder, html: `<span class="${cls}">${text}</span>` });
      return placeholder;
    };

    // 1. Comments
    if (lang === 'python') {
      escaped = escaped.replace(/(#[^\n]*)/g, (m) => saveToken('tok-comment', m));
    } else {
      escaped = escaped.replace(/(\/\/[^\n]*)/g, (m) => saveToken('tok-comment', m));
      escaped = escaped.replace(/(\/\*[\s\S]*?\*\/)/g, (m) => saveToken('tok-comment', m));
    }

    // 2. Strings
    escaped = escaped.replace(/(&quot;[\s\S]*?&quot;|&#039;[\s\S]*?&#039;|`[\s\S]*?`)/g, (m) => saveToken('tok-string', m));

    // 3. Numbers
    escaped = escaped.replace(/\b(\d+(\.\d+)?)\b/g, '<span class="tok-number">$1</span>');

    // 4. Keywords
    escaped = escaped.replace(kwRegex, '<span class="tok-keyword">$1</span>');

    // 5. Function calls
    escaped = escaped.replace(/\b([a-zA-Z_]\w*)(?=\()/g, '<span class="tok-function">$1</span>');

    // Restore tokens
    for (const tok of tokens) {
      escaped = escaped.replace(tok.placeholder, tok.html);
    }

    return escaped;
  },

  async saveCurrentFile() {
    if (!this.activeTabPath || !this.textarea) return;
    const tab = this.openTabs.find(t => t.path === this.activeTabPath);
    if (!tab) return;

    const content = this.textarea.value;
    const success = await window.FileSystem.saveFile(this.activeTabPath, content);
    if (success) {
      tab.isDirty = false;
      this.renderTabs();
      // Show mini toast
      if (window.App) window.App.showToast(`Saved ${tab.name}`);
    }
  },

  toggleSearchBar(show) {
    if (!this.searchBarEl) return;
    const isVisible = this.searchBarEl.classList.contains('active');
    const target = show !== undefined ? show : !isVisible;
    if (target) {
      this.searchBarEl.classList.add('active');
      const input = document.getElementById('search-find-input');
      if (input) input.focus();
    } else {
      this.searchBarEl.classList.remove('active');
      if (this.textarea) this.textarea.focus();
    }
  },

  findNext(query) {
    if (!query || !this.textarea) return;
    const val = this.textarea.value;
    const startPos = this.textarea.selectionEnd;
    let idx = val.toLowerCase().indexOf(query.toLowerCase(), startPos);
    if (idx === -1) {
      // wrap around
      idx = val.toLowerCase().indexOf(query.toLowerCase(), 0);
    }
    if (idx !== -1) {
      this.textarea.focus();
      this.textarea.setSelectionRange(idx, idx + query.length);
    }
  },

  replaceCurrent(query, replacement) {
    if (!query || !this.textarea) return;
    const start = this.textarea.selectionStart;
    const end = this.textarea.selectionEnd;
    const selected = this.textarea.value.substring(start, end);
    if (selected.toLowerCase() === query.toLowerCase()) {
      this.textarea.setRangeText(replacement, start, end, 'end');
      this.handleInput();
    }
    this.findNext(query);
  },

  replaceAll(query, replacement) {
    if (!query || !this.textarea) return;
    const regex = new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi');
    this.textarea.value = this.textarea.value.replace(regex, replacement);
    this.handleInput();
  },

  highlightMatches(query) {
    // optional live count indicator
    const countEl = document.getElementById('search-match-count');
    if (!countEl || !this.textarea) return;
    if (!query) {
      countEl.textContent = '';
      return;
    }
    const regex = new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi');
    const matches = this.textarea.value.match(regex);
    countEl.textContent = matches ? `${matches.length} found` : 'No matches';
  }
};

window.Editor = Editor;
