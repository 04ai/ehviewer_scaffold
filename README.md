# EhViewer High-Performance Scaffold (Rust + Flutter)

This is a high-performance Android application scaffold designed for heavy image-fetching tasks, leveraging **Rust** for CPU/IO intensive workloads (Networking, HTML Parsing, Disk Caching) and **Flutter (Impeller/Vulkan)** for 120Hz hardware-accelerated UI rendering.

> [!CAUTION]
> **Disclaimer**: This project is provided purely for personal technical research, performance testing, and open-source academic exchange. It is an architectural scaffold and does not interact with any specific real-world services out of the box.

## Architecture Highlights

1. **Rust Core (The Brain)**: 
   - **Networking**: `reqwest` with persistent connection pools and cookie management.
   - **Parsing**: `scraper` and `serde_json` for robust HTML and JSON data extraction.
   - **Caching Engine**: Memory + Disk L1/L2 LRU caching via `lru` and `tokio::fs`. Pre-fetches the next 3-5 pages of images concurrently.
2. **Flutter UI (The Canvas)**:
   - **Hardware Acceleration**: Impeller enabled by default on Android.
   - **Masonry Grid**: Fluid 120Hz staggered grid view powered by `flutter_staggered_grid_view`.
   - **Reader Mode**: InteractiveViewer with intelligent off-screen Widget disposal to prevent memory leaks (OOM) during massive image loads.
3. **FFI Bridge**: `flutter_rust_bridge` provides seamless, zero-copy asynchronous streams between Dart and Rust.

## Prerequisites

Since this scaffold includes the raw source files, you must have the following installed on your machine to build and run it:
- [Flutter SDK](https://docs.flutter.dev/get-started/install) (>= 3.22 recommended)
- [Rust & Cargo](https://rustup.rs/) (>= 1.77 recommended)
- `flutter_rust_bridge_codegen` installed globally via:
  ```bash
  cargo install 'flutter_rust_bridge_codegen@^2.0.0'
  ```

## Initialization & Build Steps

Since we have created the architectural core directly in the `ehviewer_scaffold` directory, you need to execute the following steps to finalize the Flutter engine boilerplate and compile the FFI layer:

1. **Initialize Flutter Boilerplate**:
   Open a terminal in the `ehviewer_scaffold` directory and run:
   ```bash
   flutter create --platforms android .
   ```
   *Note: This will generate `android/`, `ios/`, and other missing boilerplate while keeping our custom `pubspec.yaml` and `lib/` files intact.*

2. **Generate Rust FFI Bindings**:
   Run the FRB code generator to create the serialization logic between Dart and Rust:
   ```bash
   flutter_rust_bridge_codegen generate
   ```

3. **Enable Impeller (Vulkan) for Android**:
   In your newly generated `android/app/src/main/AndroidManifest.xml`, ensure the following meta-data is inside the `<application>` tag:
   ```xml
   <meta-data
       android:name="io.flutter.app.android.EnableImpeller"
       android:value="true" />
   ```

4. **Run the App**:
   ```bash
   flutter run -d android
   ```
   Or to build a release APK:
   ```bash
   flutter build apk --release
   ```

## Next Steps
- Implement your actual `Wbi / Sign` logic inside `rust/src/network.rs`.
- Adapt the dummy `scraper` logic in `rust/src/parser.rs` to your specific HTML structure.
- Connect the `WebViewLoginScreen` logic to persist Cloudflare clearance cookies into `reqwest`'s CookieJar.
