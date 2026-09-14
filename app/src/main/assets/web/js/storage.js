// Codex Mobile - Storage and Secure Secrets Manager
const BUILTIN_PROVIDERS = [
  { id: 'openai', name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o', isCustom: false },
  { id: 'openrouter', name: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1', model: 'anthropic/claude-3.5-sonnet', isCustom: false },
  { id: 'deepseek', name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat', isCustom: false },
  { id: 'ollama', name: 'Local Ollama', baseUrl: 'http://localhost:11434/v1', model: 'llama3', isCustom: false }
];

const Storage = {
  isAndroidNative() {
    return !!(window.AndroidBridge && typeof window.AndroidBridge.isNativeAndroid === 'function');
  },

  getApiKey() {
    if (this.isAndroidNative()) {
      try {
        const nativeKey = window.AndroidBridge.getSecureSecret('codex_ai_api_key');
        if (nativeKey && nativeKey.length > 0) return nativeKey;
      } catch (e) {
        console.warn('Native secret lookup error, falling back to local', e);
      }
    }
    return localStorage.getItem('codex_ai_api_key') || '';
  },

  setApiKey(key) {
    if (this.isAndroidNative()) {
      try {
        window.AndroidBridge.setSecureSecret('codex_ai_api_key', key || '');
      } catch (e) {
        console.warn('Native secret store error', e);
      }
    }
    // Store locally as well (or fallback)
    if (key) {
      localStorage.setItem('codex_ai_api_key', key);
    } else {
      localStorage.removeItem('codex_ai_api_key');
    }
  },

  // Custom AI Providers Management
  getCustomProviders() {
    try {
      const raw = localStorage.getItem('codex_custom_providers');
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed : [];
    } catch (e) {
      console.warn('Failed to parse custom providers', e);
      return [];
    }
  },

  saveCustomProvider(provider) {
    if (!provider || !provider.name || !provider.baseUrl) {
      throw new Error("Provider name and Base URL are required.");
    }

    const providers = this.getCustomProviders();
    const id = provider.id || `custom_${Date.now()}`;
    const cleanBaseUrl = provider.baseUrl.trim().replace(/\/+$/, '');
    const cleanName = provider.name.trim();
    const cleanModel = (provider.model || '').trim() || 'gpt-4o';
    const cleanApiKey = (provider.apiKey || '').trim();
    const headers = provider.headers || null;

    const providerObj = {
      id,
      name: cleanName,
      baseUrl: cleanBaseUrl,
      model: cleanModel,
      apiKey: cleanApiKey,
      headers: headers,
      isCustom: true,
      updatedAt: Date.now()
    };

    const existingIndex = providers.findIndex(p => p.id === id);
    if (existingIndex >= 0) {
      providers[existingIndex] = providerObj;
    } else {
      providers.push(providerObj);
    }

    localStorage.setItem('codex_custom_providers', JSON.stringify(providers));
    return providerObj;
  },

  deleteCustomProvider(id) {
    let providers = this.getCustomProviders();
    providers = providers.filter(p => p.id !== id);
    localStorage.setItem('codex_custom_providers', JSON.stringify(providers));

    if (this.getActiveProviderId() === id) {
      this.setActiveProvider('openai');
    }
  },

  getCustomProviderById(id) {
    const list = this.getCustomProviders();
    return list.find(p => p.id === id) || null;
  },

  getAllProviders() {
    return [...BUILTIN_PROVIDERS, ...this.getCustomProviders()];
  },

  getActiveProviderId() {
    return localStorage.getItem('codex_active_provider_id') || 'openai';
  },

  setActiveProvider(id) {
    const all = this.getAllProviders();
    let target = all.find(p => p.id === id);
    if (!target) {
      target = BUILTIN_PROVIDERS[0];
      id = target.id;
    }

    localStorage.setItem('codex_active_provider_id', id);
    if (target.baseUrl) {
      localStorage.setItem('codex_ai_base_url', target.baseUrl.trim().replace(/\/+$/, ''));
    }
    if (target.model) {
      localStorage.setItem('codex_ai_model', target.model.trim());
    }
    if (target.isCustom && target.apiKey !== undefined) {
      this.setApiKey(target.apiKey);
    }
    if (target.headers) {
      localStorage.setItem('codex_active_provider_headers', typeof target.headers === 'string' ? target.headers : JSON.stringify(target.headers));
    } else {
      localStorage.removeItem('codex_active_provider_headers');
    }
    return target;
  },

  getActiveProvider() {
    const activeId = this.getActiveProviderId();
    const all = this.getAllProviders();
    return all.find(p => p.id === activeId) || BUILTIN_PROVIDERS[0];
  },

  getCustomHeaders() {
    const raw = localStorage.getItem('codex_active_provider_headers');
    if (!raw) return null;
    try {
      return typeof raw === 'object' ? raw : JSON.parse(raw);
    } catch (e) {
      return null;
    }
  },

  getConfig() {
    return {
      baseUrl: localStorage.getItem('codex_ai_base_url') || 'https://api.openai.com/v1',
      apiKey: this.getApiKey(),
      model: localStorage.getItem('codex_ai_model') || 'gpt-4o',
      activeProviderId: this.getActiveProviderId(),
      customHeaders: this.getCustomHeaders(),
      systemPrompt: localStorage.getItem('codex_ai_system_prompt') || 
        'You are Codex, an expert software engineer and architect on Android. CRITICAL: You do not have external XML function call tools. Never output XML tool tags, <function_calls>, <dots_function_call>, or <invoke>. Converse directly in clear Markdown. When proposing code changes, clearly indicate the exact file path and action format (### Action: CREATE, ### Action: MODIFY). Write clean, production-grade code.',
      fontSize: parseInt(localStorage.getItem('codex_editor_font_size') || '14', 10),
      tabSize: parseInt(localStorage.getItem('codex_editor_tab_size') || '2', 10),
      wordWrap: localStorage.getItem('codex_editor_word_wrap') === 'true'
    };
  },

  saveConfig(cfg) {
    if (cfg.baseUrl !== undefined) localStorage.setItem('codex_ai_base_url', cfg.baseUrl.trim().replace(/\/+$/, ''));
    if (cfg.apiKey !== undefined) this.setApiKey(cfg.apiKey.trim());
    if (cfg.model !== undefined) localStorage.setItem('codex_ai_model', cfg.model.trim());
    if (cfg.activeProviderId !== undefined) localStorage.setItem('codex_active_provider_id', cfg.activeProviderId);
    if (cfg.systemPrompt !== undefined) localStorage.setItem('codex_ai_system_prompt', cfg.systemPrompt);
    if (cfg.fontSize !== undefined) localStorage.setItem('codex_editor_font_size', cfg.fontSize.toString());
    if (cfg.tabSize !== undefined) localStorage.setItem('codex_editor_tab_size', cfg.tabSize.toString());
    if (cfg.wordWrap !== undefined) localStorage.setItem('codex_editor_word_wrap', cfg.wordWrap ? 'true' : 'false');
  }
};

window.Storage = Storage;
