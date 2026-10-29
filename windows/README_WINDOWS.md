# GUPet для Windows

## Как собрать:
1. Установите Rust (https://rustup.rs/)
2. Установите Visual Studio Build Tools (с компонентом "Desktop development with C++")
3. Установите WebView2 Runtime (https://developer.microsoft.com/en-us/microsoft-edge/webview2/)
4. Откройте терминал в папке `src-tauri` и выполните: `cargo tauri build`
5. Готовый .exe будет в `target/release/bundle/`
