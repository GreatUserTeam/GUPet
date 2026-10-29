// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

fn main() {
  tauri::Builder::default()
    .setup(|app| {
      // Окно настроек
      let _settings = WebviewWindowBuilder::new(app, "settings", WebviewUrl::App("index.html".into()))
        .title("GUPet Settings")
        .inner_size(450.0, 800.0)
        .resizable(true)
        .build()?;

      // Оверлей (прозрачный, всегда сверху)
      let _overlay = WebviewWindowBuilder::new(app, "overlay", WebviewUrl::App("overlay.html".into()))
        .title("GUPet Overlay")
        .transparent(true)
        .decorations(false)
        .always_on_top(true)
        .skip_taskbar(true)
        .inner_size(800.0, 600.0)
        .build()?;

      Ok(())
    })
    .run(tauri::generate_context!())
    .expect("error while running tauri application");
}
