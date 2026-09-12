// Codex Mobile - OpenAI-Compatible AI Client & Chat Coordinator
const AIClient = {
  conversationHistory: [], // array of { role: 'user' | 'assistant' | 'system', content: string }
  isGenerating: false,
  selectedContextFiles: new Set(),

  init() {
    this.conversationHistory = [];
  },

  // Test endpoint connection and auth
  async testConnection(config) {
    const baseUrl = (config.baseUrl || 'https://api.openai.com/v1').replace(/\/+$/, '');
    const apiKey = config.apiKey || '';
    const model = config.model || 'gpt-4o';
    const customHeaders = config.headers || null;

    if (!baseUrl) {
      throw new Error("API Base URL is required.");
    }

    const headers = {
      'Content-Type': 'application/json'
    };
    if (apiKey) {
      headers['Authorization'] = `Bearer ${apiKey}`;
    }
    if (customHeaders && typeof customHeaders === 'object') {
      Object.assign(headers, customHeaders);
    }

    const startTime = Date.now();

    try {
      const modelsUrl = `${baseUrl}/models`;
      const response = await fetch(modelsUrl, {
        method: 'GET',
        headers
      });

      const elapsed = Date.now() - startTime;

      if (response.ok) {
        const data = await response.json();
        const count = data.data && Array.isArray(data.data) ? data.data.length : 0;
        return {
          success: true,
          status: response.status,
          latency: `${elapsed}ms`,
          message: `Endpoint reachable (${count} models detected).`,
          models: data.data || []
        };
      }

      if (response.status === 404 || response.status === 405) {
        return await this.probeChatCompletion(baseUrl, apiKey, model, headers);
      }

      const errText = await response.text();
      let errMsg = `HTTP ${response.status} ${response.statusText}`;
      try {
        const errJson = JSON.parse(errText);
        if (errJson.error && errJson.error.message) {
          errMsg = errJson.error.message;
        }
      } catch (e) {}

      return {
        success: false,
        status: response.status,
        message: errMsg
      };
    } catch (netErr) {
      return {
        success: false,
        status: 0,
        message: `Network error or CORS restriction: ${netErr.message}. Verify that the URL is correct and server allows cross-origin requests.`
      };
    }
  },

  async probeChatCompletion(baseUrl, apiKey, model, headers) {
    const startTime = Date.now();
    try {
      const chatUrl = `${baseUrl}/chat/completions`;
      const response = await fetch(chatUrl, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          model,
          messages: [{ role: 'user', content: 'Ping' }],
          max_tokens: 5
        })
      });
      const elapsed = Date.now() - startTime;

      if (response.ok) {
        return {
          success: true,
          status: response.status,
          latency: `${elapsed}ms`,
          message: `Endpoint and model (${model}) verified successfully!`
        };
      }

      const errText = await response.text();
      let errMsg = `HTTP ${response.status}`;
      try {
        const errJson = JSON.parse(errText);
        if (errJson.error && errJson.error.message) errMsg = errJson.error.message;
      } catch (e) {}

      return {
        success: false,
        status: response.status,
        message: errMsg
      };
    } catch (e) {
      return {
        success: false,
        status: 0,
        message: `Connection failed: ${e.message}`
      };
    }
  },

  // Build context prompt with attached files and real project directory awareness
  buildContextMessage(userPrompt, actionType = 'custom') {
    let contextHeader = '';

    const projName = window.FileSystem && window.FileSystem.currentProject
      ? window.FileSystem.currentProject.name
      : 'Active Project';

    const projStructure = window.FileSystem ? window.FileSystem.getProjectStructureSummary() : '';

    contextHeader += `Current Active Project Folder: "${projName}"\n`;
    contextHeader += `Current Project Files:\n${projStructure}\n\n`;

    // Attach active file or user-selected context files
    const contextFiles = [];
    if (this.selectedContextFiles.size > 0) {
      for (const p of this.selectedContextFiles) {
        const content = window.FileSystem ? window.FileSystem.getFileContent(p) : null;
        if (content !== null) contextFiles.push({ path: p, content });
      }
    } else if (window.FileSystem && window.FileSystem.activeFilePath) {
      const activePath = window.FileSystem.activeFilePath;
      const content = window.FileSystem.getFileContent(activePath);
      if (content !== null) contextFiles.push({ path: activePath, content });
    }

    if (contextFiles.length > 0) {
      contextHeader += `--- Attached Context Files ---\n`;
      for (const f of contextFiles) {
        contextHeader += `--- FILE: ${f.path} ---\n${f.content}\n--- END FILE: ${f.path} ---\n\n`;
      }
    }

    let instruction = userPrompt;
    if (actionType === 'explain') {
      instruction = `Explain the following code clearly, highlighting key logic, architecture, and potential edge cases:\n\n${userPrompt}`;
    } else if (actionType === 'fix') {
      instruction = `Inspect the following code for bugs, logic errors, or syntax issues. Propose fixed code for the project folder:\n\n${userPrompt}`;
    } else if (actionType === 'refactor') {
      instruction = `Refactor the following code for cleaner structure, readability, and performance. Keep behavior intact:\n\n${userPrompt}`;
    } else if (actionType === 'generate') {
      instruction = `Generate complete, production-grade files for the following request directly for the project folder "${projName}":\n\n${userPrompt}`;
    }

    const rules = `
IMPORTANT DIRECTIVES FOR CODEX AI:
1. All changes will be reviewed by the user and written directly into the selected phone project directory "${projName}" upon tapping "Apply Changes".
2. When creating new files, updating existing files, or deleting files, ALWAYS use this exact structured format for EACH file change:

### Action: CREATE
### File: relative/path/to/file.ext
\`\`\`language
[complete, full content for this file]
\`\`\`

### Action: MODIFY
### File: relative/path/to/file.ext
\`\`\`language
[complete updated content for this file]
\`\`\`

### Action: DELETE
### File: relative/path/to/file.ext

3. Preserve clean and correct folder structures (e.g., css/style.css, js/app.js, src/...).
4. Do NOT output truncated code or placeholders like "// rest of code unchanged". Provide the entire usable code for every created or modified file.
`;

    return `${contextHeader}${instruction}\n\n${rules}`;
  },

  // Send message to AI endpoint
  async sendMessage(prompt, actionType = 'custom', onChunk = null) {
    if (this.isGenerating) throw new Error("A generation is already in progress.");

    const config = window.Storage ? window.Storage.getConfig() : {};
    const baseUrl = (config.baseUrl || 'https://api.openai.com/v1').replace(/\/+$/, '');
    const apiKey = config.apiKey || '';
    const model = config.model || 'gpt-4o';
    const systemPrompt = config.systemPrompt;
    const customHeaders = config.customHeaders;

    const userMessageContent = this.buildContextMessage(prompt, actionType);

    // Prepare messages array
    const messages = [];
    if (systemPrompt) {
      messages.push({ role: 'system', content: systemPrompt });
    }
    for (const msg of this.conversationHistory) {
      messages.push({ role: msg.role, content: msg.content });
    }
    messages.push({ role: 'user', content: userMessageContent });

    const headers = {
      'Content-Type': 'application/json'
    };
    if (apiKey) {
      headers['Authorization'] = `Bearer ${apiKey}`;
    }
    if (customHeaders && typeof customHeaders === 'object') {
      Object.assign(headers, customHeaders);
    }

    this.isGenerating = true;

    if (window.Terminal) {
      window.Terminal.log(`Dispatching AI prompt to model ${model} at ${baseUrl}...`, "api");
    }

    try {
      const response = await fetch(`${baseUrl}/chat/completions`, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          model,
          messages,
          temperature: 0.2
        })
      });

      if (!response.ok) {
        const errText = await response.text();
        let errMsg = `API error (${response.status} ${response.statusText})`;
        try {
          const errJson = JSON.parse(errText);
          if (errJson.error && errJson.error.message) {
            errMsg = errJson.error.message;
          }
        } catch (e) {}

        if (response.status === 401) {
          errMsg = `Unauthorized (401): Invalid API key. Please check your API Key in Settings.`;
        } else if (response.status === 404) {
          errMsg = `Not Found (404): Model "${model}" or endpoint "${baseUrl}" not found.`;
        } else if (response.status === 429) {
          errMsg = `Rate limit exceeded (429): Quota limit reached or requests too frequent.`;
        }

        throw new Error(errMsg);
      }

      const data = await response.json();
      const assistantMessage = data.choices && data.choices[0] && data.choices[0].message
        ? data.choices[0].message.content
        : "No response generated by model.";

      this.conversationHistory.push({ role: 'user', content: prompt });
      this.conversationHistory.push({ role: 'assistant', content: assistantMessage });

      if (window.Terminal) {
        window.Terminal.log(`AI completion received (${assistantMessage.length} characters).`, "api");
      }

      return assistantMessage;
    } catch (err) {
      if (window.Terminal) {
        window.Terminal.log(`AI Request failed: ${err.message}`, "error");
      }
      throw err;
    } finally {
      this.isGenerating = false;
    }
  },

  clearHistory() {
    this.conversationHistory = [];
    if (window.Terminal) {
      window.Terminal.log("Cleared AI conversation history.", "system");
    }
  }
};

window.AIClient = AIClient;
