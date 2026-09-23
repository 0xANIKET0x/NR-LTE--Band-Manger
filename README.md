# NR Band Manager (Root)

![NR Band Manager](https://img.shields.io/badge/Status-Active-brightgreen)
![Android Requirement](https://img.shields.io/badge/Android-15%2B%20%7C%20MIUI%20%7C%20HyperOS-blue)
![Root Requirement](https://img.shields.io/badge/Root-Magisk%20%7C%20KernelSU-red)

A powerful, root-enabled cellular band and network mode manager specifically designed to bypass strict OEM restrictions on modern Android 15 devices (particularly Xiaomi / Poco devices running HyperOS or MIUI).

## ⚠️ Requirements
- **Root Access is strictly required** (Magisk, KernelSU, or APatch). The app utilizes `app_process` injection to bypass Android hidden API restrictions.
- Tested aggressively on **Android 15 (SDK 35)**.

## 🚀 Key Features

* **Native 5G NR & 4G LTE Band Locking:** Select specific bands (like n78, n28, B3, B40) and natively inject the lock into the modem's Telephony framework.
* **⚡ Force 5G++ (Max Speed / Carrier Aggregation):** A specialized macro designed for networks like Jio 5G SA. It forces the device into Standalone NR Mode, injects aggressive Qualcomm Vendor properties (`persist.vendor.radio.nr_ca 1`, `sa_mode 1`), and spoofs UI settings to ensure maximum Carrier Aggregation and the coveted `5G++` status bar icon.
* **Global SIM Routing:** A top-level SIM selector effortlessly routes all network commands to the correct hardware slot without redundant UI dropdowns.
* **Persistent Boot Lock:** Option to save your chosen bands and automatically re-apply the native locks in the background every time the phone boots up.
* **Hard Network Mode Forcing:** Bypasses `IModemSmartEngine` overrides by injecting `preferred_network_mode` settings globally and forcing immediate modem synchronization.
* **One-Click Reset:** Instantly wipe all band locks, custom network modes, and saved preferences to restore the modem to its factory hardware state.

## 🛠️ Why this exists?
On modern Android versions, standard hidden Telephony APIs (`setSystemSelectionChannels`) are strictly blocked by `SecurityException`s or `UnsupportedAppUsage` protections, and legacy AT Command ports (`/dev/at_mdm0`) are physically blocked by SELinux and modern firmware.

This app solves this by compiling a minimal Java payload (`BandLocker.dex`) and executing it directly within the root shell context via `app_process`. This allows the script to legally bind to the `ITelephony` service as the Android System and force the hardware band locks.

## 📥 Installation
You can grab the pre-compiled APK directly from this repository:
1. Download `NR_Band_Manager.apk` from the root of this repo.
2. Install the APK on your rooted device.
3. Grant Superuser permissions when prompted.

## 🏗️ Building from Source
This project uses a standard Android SDK environment with a custom PowerShell build script (`build.ps1`). It does not rely on Gradle.

1. Ensure `aapt`, `d8`, `javac`, and `zipalign` are in your PATH.
2. Run `.\build.ps1` to compile the resources, DEX the payloads, and sign the final APK.