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
   - The `build/apk/` output folder is always visible in the explorer so generated APKs can be found.

5. **Validated On-Device APK Builder (Gradle pipeline)**
   - HopWeb-style workflow: select the project folder, tap **Build APK**, and the system stages a temporary Android Gradle project around your web files (your sources are never modified), then runs a real `:app:assembleDebug/Release` build.
   - No manual `aapt2`/`d8`/`apksigner` hunting: the app detects the build environment (JDK 17+, Gradle 8.7, SDK platform 34 + build-tools 34.0.0, AGP 8.5.2) and automatically provisions everything it can — SDK command-line tools, SDK packages, and the Gradle distribution are downloaded once and cached under `codex-android-env`. Only a missing JDK needs one Termux command (`pkg install openjdk-17`), offered with a guided script + Open-Termux button.
   - Build options (application ID, version code/name, debug/release) are validated before compiling; stale artifacts and previous outputs are excluded from packaging.
   - Every APK passes automated installability validation (ZIP integrity, binary manifest, `classes.dex`, `resources.arsc`, v1+v2 signature, 4-byte alignment) before success is reported — failures show the real Gradle/validation error instead of a fake success.
   - The validated APK is saved to `build/apk/` inside the project folder (explorer auto-refreshes) plus a Download copy; **Install APK** opens the system installer via a secure `FileProvider` URI after the install-permission check, with **Share APK** and **APK Location** actions alongside.

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
  - `ApkBuildHelper.kt`: Gradle-pipeline orchestration (wrapper staging, environment setup, build, validation) with pre-install readiness gates.
  - `AndroidBuildEnvironment.kt`: Build-environment detection + automatic provisioning (JDK/Gradle/SDK, cached).
  - `GradleWrapperProject.kt`: Temporary Android wrapper project generator (manifest, WebView shell, icons, Gradle files).
  - `ApkBuildOptions.kt`: Build options plus application ID / version / SDK validation.
  - `ApkValidator.kt`: Post-build installability checks (structure, DEX, signature, alignment).
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
