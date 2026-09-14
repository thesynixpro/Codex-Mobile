# Codex Mobile - AI Coding Workspace for Android

Codex Mobile is a mobile-first AI coding workspace designed specifically for Android phones, tablets, and mobile browsers. It delivers a modern, dark developer-tool aesthetic with an integrated code editor, file explorer, OpenAI-compatible AI assistant, safe diff/patch review system, and an on-device developer terminal.

---

## Core Capabilities

1. **Configurable AI Provider**
   - Compatible with any OpenAI-style REST endpoint (`/chat/completions`, `/models`).
   - Supports custom base URLs (OpenAI, OpenRouter, DeepSeek, Local Ollama, Groq, vLLM, etc.).
   - Secure credential management: API keys in the native Android app are encrypted with hardware-backed AES-256 GCM using the **Android KeyStore**.
   - Built-in connection tester to diagnose latency, reachability, and authentication status.

2. **Mobile-First Code Editor**
   - Touch keyboard coding toolbar with one-tap syntax keys (`Tab`, `{ }`, `( )`, `[ ]`, `<`, `>`, `=`, `;`, `:`, `->`, `undo`, `redo`).
   - Line numbers with synchronized scrolling.
   - Syntax token highlighting across JavaScript, TypeScript, Python, Kotlin, Java, HTML, CSS, PHP, and Markdown.
   - Multi-tab file management with dirty state indicators and search/replace.
   - Built for `windowSoftInputMode="adjustResize"` to ensure the mobile keyboard never obscures the editor or input fields.

3. **Safe AI Diff & Patch Review**
   - The AI inspects the active project context and proposes file changes.
   - Every file change is rendered in a visual **diff viewer** with additions (`+`) and deletions (`-`).
   - Two-button workflow (**Apply Changes** / **Reject**) ensures code is never overwritten silently.

4. **Android File System & Storage Access Framework (SAF)**
   - Mounts local Android device directories directly into the IDE using `DocumentsContract` and `OpenDocumentTree`.
   - File read, write, create, and delete operations are bridged asynchronously to native Android storage.
   - Includes browser File System Access API and folder import fallbacks when run outside the APK.

5. **Terminal & Developer Console**
   - Integrated developer console with commands: `help`, `status`, `ls`, `cat`, `test-api`, `termux-info`, `date`, `clear`.
   - Real-time logging of file operations, API network requests, and system events.

---

## Building and Running on Android via Termux

You can build and package Codex Mobile directly on an Android device using Termux.

### Prerequisites in Termux
```bash
# Update Termux packages
pkg update && pkg upgrade -y

# Install OpenJDK 17, Gradle, Git, and Termux API
pkg install openjdk-17 gradle git termux-api -y

# Verify Java version
java -version
```

### Clone and Build the Project
```bash
# Navigate to your workspace directory
cd ~

# Clone the repository
git clone https://github.com/example/codex-mobile.git
cd codex-mobile

# Run the Gradle build directly on device
gradle assembleDebug
```

### Installing the Generated APK
```bash
# Open and install the generated debug APK
termux-open app/build/outputs/apk/debug/app-debug.apk
```

---

## Architecture

- **Native Layer (`app/src/main/java/com/example/`)**:
  - `MainActivity.kt`: Hosts the hardware-accelerated WebView, handles `WebViewClient`, edge-to-edge window insets, and Android back navigation.
  - `AndroidBridge.kt`: JavaScript interface (`@JavascriptInterface`) bridging file operations and secure storage to the web UI.
  - `DocumentTreeHelper.kt`: Implements Android Storage Access Framework (SAF) tree traversal and content resolver streams.
  - `SecureStorageHelper.kt`: Encrypts API keys with `AES/GCM/NoPadding` using cryptographic keys generated in the `AndroidKeyStore`.

- **Web Workspace Layer (`app/src/main/assets/web/`)**:
  - `index.html`: Mobile-first responsive IDE shell (no emojis, SVG/PNG assets only).
  - `css/app.css`: Dark developer theme with adaptive mobile bottom navigation and desktop split layouts.
  - `js/icons.js`: Centralized SVG icon system.
  - `js/bridge.js`: Promise-based native bridge interface.
  - `js/storage.js`: Secure secrets and preferences manager.
  - `js/file-system.js`: Virtual and native filesystem controller.
  - `js/editor.js`: Touch editor engine with virtual coding toolbar.
  - `js/diff-patch.js`: Line diff parser and proposal card renderer.
  - `js/ai-client.js`: OpenAI-compatible completions client with attached context coordinator.
  - `js/terminal.js`: Interactive developer console.
  - `js/app.js`: Main app orchestrator.

---

## Testing & Verification

To run unit and Robolectric tests locally:
```bash
gradle :app:testDebugUnitTest
```
