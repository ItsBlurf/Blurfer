# Blurfer

Blurfer is a modern, cross-platform payload manager and sender for Windows, Linux, and Android. It sends files as raw bytes over TCP and provides a highly customisable, reusable payload queue with per-file ports, delays, ordering, and built-in GitHub release integration.

No payload files are bundled with Blurfer.

## Download

Get the latest build from the [Releases](https://github.com/ItsBlurf/Blurfer/releases/latest) page.

| Platform | File | Notes |
| --- | --- | --- |
| Windows | `Blurfer.exe` | Portable executable (built using CustomTkinter) |
| Linux | `Blurfer-x86_64.AppImage` | Recommended Linux packaging |
| Linux | `Blurfer-linux-x86_64.bin` | Standalone Linux binary |
| Android | `Blurfer.apk` | Android 6.0 or newer (conforming to Material guidelines) |
| macOS | [duperin/payload-sender](https://github.com/duperin/payload-sender/) | Native macOS alternative. Thanks to duperin. |

## Screenshots

### Desktop (v2.0)

<img src="docs/screenshots/desktop.png" alt="Blurfer desktop interface" width="900">

### Android (v2.0)

<img src="docs/screenshots/android.jpeg" alt="Blurfer Android interface" width="320">

## Key Features

### 📡 Payload Injection
- **Queue Management**: Add, order, and preview payload queues before sending.
- **Port & Delay Settings**: Configure custom TCP ports and pre-send delays (in seconds) per payload.
- **Batch Operations**: Send a single selected payload, a batch of checked payloads, or the entire queue at once.

### 📥 Built-in Download Hub
- **GitHub Release Scraper**: Fetch, browse, and search official releases from repositories directly in-app.
- **Selective Downloads**: Lists release versions and filters assets to show only relevant payloads (`.elf`, `.bin`, and `.js`).
- **Custom Repositories Manager**: Register custom GitHub repositories (e.g. `user/repo`). The app automatically scrapes and processes their releases.
- **Rate Limit Bypass**: Optional Personal Access Token (PAT) input field to increase GitHub API rate limits from 60 to 5,000 queries per hour.

### 📱 Android Storage & UI Integration
- **Storage Access Framework (SAF)**: Native folder selection and download directory integration. Prevents duplicate file name conflicts by cleanly overriding existing versions on download.
- **Material Dark Slate Theme**: Gorgeous programmatic dark layout built with custom card components.
- **Dynamic Inset Safety**: Uses Android window insets to automatically pad the UI around notification status bars and system navigation bars.
- **Accessibility Friendly**: Spaced controls, inputs (44dp+), and primary buttons (48dp+) conforming to standard touch-target sizing.

## Quick Start

1. Download the build for your platform.
2. Choose the folder containing your payload files (using Storage Access Framework on Android).
3. Enter the target host or IP address of your console.
4. Review the queue, ports, and delays.
5. Send the selected payloads or the full queue.

The default port is `9021`. A payload's delay is applied immediately before that payload is sent.

## Build From Source

### Requirements
- **Windows & Linux**: Python 3.10 or newer (with `customtkinter` and `tkinterdnd2`)
- **Android**: OpenJDK 17, Android SDK, and Gradle wrapper

### Windows:
```powershell
.\build_windows.ps1
```

### Linux / WSL:
```bash
chmod +x ./build_linux.sh
./build_linux.sh
```

### Android:
```powershell
$env:ANDROID_HOME = "C:\Users\Blurf\AppData\Local\Android\Sdk"
.\build_android.ps1
```
or:
```bash
./build_android.sh
```

Build outputs are written to `dist/`.

## Responsibility

Blurfer only sends files selected by the user. Use it only with devices and software you own or are authorized to test. Payload behavior is outside the scope of this project.
