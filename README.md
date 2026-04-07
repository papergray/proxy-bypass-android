# Proxy & DNS Bypass Android App

This is a native Android application written in Kotlin that helps bypass network filters like Sophos by fetching free proxies and setting custom DNS servers at runtime.

## Features
- **Free Proxy Fetcher**: Automatically fetches HTTP and SOCKS5 proxies from multiple public sources.
- **Custom DNS**: Allows users to specify a custom DNS server (e.g., Cloudflare 1.1.1.1 or Google 8.8.8.8) to bypass DNS-based filtering.
- **VPN Service Integration**: Uses Android's `VpnService` API to route traffic through the selected proxy and DNS.

## How to Compile
1. **Prerequisites**:
   - Android Studio (Hedgehog or newer recommended)
   - JDK 17
   - Android SDK 34

2. **Steps**:
   - Clone this repository.
   - Open the project in Android Studio.
   - Wait for Gradle sync to complete.
   - Connect an Android device or start an emulator.
   - Click the **Run** button (green play icon).

## Technical Details
- **Language**: Kotlin
- **Networking**: OkHttp, Retrofit
- **Concurrency**: Kotlin Coroutines
- **UI**: ViewBinding, Material Components

## Disclaimer
This app is for educational purposes only. Use it responsibly and in accordance with your local laws and network policies.
