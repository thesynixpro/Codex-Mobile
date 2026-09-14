// Codex Mobile - OpenAI-Compatible AI Client & Chat Coordinator
const AIClient = {
  conversationHistory: [], // array of { role: 'user' | 'assistant' | 'system', content: string }
  isGenerating: false,
  selectedContextFiles: new Set(),
  ignoreActiveFileContext: false,

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

  isConversational(prompt) {
    if (!prompt) return false;
    const clean = prompt.trim().toLowerCase();
    return /^(hi|hello|hey|heya|howdy|sup|good\s+(morning|afternoon|evening)|who are you|what can you do|help|test|ok|thanks|thank you)[\s!.?]*$/i.test(clean);
  },

  // Build context prompt with attached files and real project directory awareness
  buildContextMessage(userPrompt, actionType = 'custom') {
    const projName = window.FileSystem && window.FileSystem.currentProject
      ? window.FileSystem.currentProject.name
      : 'Active Project';

    // Handle simple greetings conversationally without overloading the model with project modification rules
    if (actionType === 'custom' && this.isConversational(userPrompt)) {
      return `Current Active Project Folder: "${projName}"
User greeting: "${userPrompt}"

Instructions for Codex AI:
Respond warmly and conversationally in clear Markdown. Introduce yourself as Codex AI, the mobile software engineer and architect. Let the user know you are ready to help them build features, edit files, review code, fix bugs, or build an APK for "${projName}".
CRITICAL: Do NOT output XML tool calls, <function_calls>, <dots_function_call>, or <invoke> tags. Speak directly to the user.`;
    }

    let contextHeader = '';
    const projStructure = window.FileSystem ? window.FileSystem.getProjectStructureSummary() : '';

    contextHeader += `Current Active Project Folder: "${projName}"\n`;
    contextHeader += `Current Project Files:\n${projStructure}\n\n`;

    // Attach active file or user-selected context files (unless actively ignored)
    const contextFiles = [];
    if (this.selectedContextFiles.size > 0) {
      for (const p of this.selectedContextFiles) {
        const content = window.FileSystem ? window.FileSystem.getFileContent(p) : null;
        if (content !== null) contextFiles.push({ path: p, content });
      }
    } else if (window.FileSystem && window.FileSystem.activeFilePath && !this.ignoreActiveFileContext) {
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
      instruction = `Inspect the following code or project for bugs, logic errors, syntax issues, and potential edge cases. Propose fixed code for the project folder:\n\n${userPrompt}`;
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
5. CRITICAL: You do NOT have external XML function call tools. NEVER output XML tags, <function_calls>, <dots_function_call>, or <invoke> tags. Output code and explanations directly in Markdown.
`;

    return `${contextHeader}${instruction}\n\n${rules}`;
  },

  sanitizeAiOutput(text) {
    if (!text) return '';
    let cleaned = text
      .replace(/<dots_function_call>[\s\S]*?<\/dots_function_call>/gi, '')
      .replace(/<dots_function_call>/gi, '')
      .replace(/<\/dots_function_call>/gi, '')
      .replace(/<function_calls>[\s\S]*?<\/function_calls>/gi, '')
      .replace(/<function_calls>/gi, '')
      .replace(/<\/function_calls>/gi, '')
      .replace(/<invoke[^>]*>[\s\S]*?<\/invoke>/gi, '');

    cleaned = cleaned.trim();
    if (!cleaned) {
      return "Hello! I am Codex, your mobile AI coding assistant. How can I help you with your project today? I can write code, create files, explain architecture, or build an APK.";
    }
    return cleaned;
  },

  async handleToolCallsOrSanitize(rawText, baseUrl, headers, model, messages) {
    if (!rawText) return "No response generated by model.";

    // Check if the model attempted an XML tool call to read a file
    const readMatch = /<invoke\s+name=["'](?:read_file|get_file|cat)["']>[\s\S]*?<parameter\s+name=["']path["']>([\s\S]*?)<\/parameter>[\s\S]*?<\/invoke>/i.exec(rawText);
    if (readMatch && window.FileSystem) {
      const targetPath = readMatch[1].trim();
      const content = window.FileSystem.getFileContent(targetPath);
      if (content !== null) {
        if (window.Terminal) {
          window.Terminal.log(`Resolving AI tool call: read_file("${targetPath}")...`, "api");
        }
        try {
          const followUpMessages = [
            ...messages,
            { role: 'assistant', content: rawText },
            {
              role: 'user',
              content: `[Tool Result: Content of "${targetPath}"]:\n\`\`\`\n${content}\n\`\`\`\nPlease continue answering the user's prompt directly in clean Markdown without emitting XML tool tags or <function_calls>.`
            }
          ];
          const followUpResp = await fetch(`${baseUrl}/chat/completions`, {
            method: 'POST',
            headers,
            body: JSON.stringify({
              model,
              messages: followUpMessages,
              temperature: 0.2
            })
          });
          if (followUpResp.ok) {
            const followUpData = await followUpResp.json();
            const secondReply = followUpData.choices?.[0]?.message?.content;
            if (secondReply) {
              return this.sanitizeAiOutput(secondReply);
            }
          }
        } catch (e) {
          console.warn("Tool follow-up resolution failed", e);
        }
      }
    }

    return this.sanitizeAiOutput(rawText);
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
      const rawAssistantMessage = data.choices && data.choices[0] && data.choices[0].message
        ? data.choices[0].message.content
        : "No response generated by model.";

      const assistantMessage = await this.handleToolCallsOrSanitize(rawAssistantMessage, baseUrl, headers, model, messages);

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
