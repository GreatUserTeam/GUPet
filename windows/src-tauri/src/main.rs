#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
#![recursion_limit = "512"]

mod skins;
mod store;
mod system;

#[cfg(windows)]
use raw_window_handle::{HasWindowHandle, RawWindowHandle};
use serde::Serialize;
use serde_json::{json, Value};
#[cfg(windows)]
use std::collections::BTreeSet;
#[cfg(windows)]
use std::ffi::c_void;
use std::fs;
#[cfg(windows)]
use std::path::Path;
#[cfg(windows)]
use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::sync::Mutex;
#[cfg(windows)]
use std::time::Duration;
use std::time::{SystemTime, UNIX_EPOCH};
use store::{SettingsStore, BUILTIN_SKIN};
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder, WindowEvent};
#[cfg(windows)]
use windows::core::PWSTR;
#[cfg(windows)]
use windows::Win32::Foundation::{CloseHandle, BOOL, HWND, LPARAM, POINT};
#[cfg(windows)]
use windows::Win32::Graphics::Dwm::{DwmGetWindowAttribute, DWMWA_CLOAKED};
#[cfg(windows)]
use windows::Win32::Graphics::Gdi::ClientToScreen;
#[cfg(windows)]
use windows::Win32::System::Threading::{
    GetCurrentProcessId, OpenProcess, QueryFullProcessImageNameW, PROCESS_NAME_WIN32,
    PROCESS_QUERY_LIMITED_INFORMATION,
};
#[cfg(windows)]
use windows::Win32::UI::WindowsAndMessaging::{
    EnumWindows, GetClassNameW, GetCursorPos, GetForegroundWindow, GetWindowLongPtrW,
    GetWindowTextLengthW, GetWindowTextW, GetWindowThreadProcessId, IsWindowVisible,
    SetWindowLongPtrW, SetWindowPos, GWL_EXSTYLE, SWP_FRAMECHANGED, SWP_NOACTIVATE, SWP_NOMOVE,
    SWP_NOSIZE, SWP_NOZORDER, WS_EX_LAYERED, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TRANSPARENT,
};

struct AppState {
    store: Mutex<SettingsStore>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CreatedSkin {
    ok: bool,
    id: Option<String>,
    error: Option<String>,
}

fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

fn change_active_skins(store: &mut SettingsStore, ids: Vec<String>) -> Result<Vec<String>, String> {
    let mut valid = Vec::new();
    for id in ids {
        let id = id.trim().to_string();
        if id.is_empty() || valid.contains(&id) {
            continue;
        }
        if id == BUILTIN_SKIN || skins::find_skin_folder(&id).is_some() {
            valid.push(id);
        }
    }
    if valid.is_empty() {
        valid.push(BUILTIN_SKIN.to_string());
    }
    let custom_skin = valid
        .iter()
        .filter(|id| id.as_str() != BUILTIN_SKIN)
        .find(|id| {
            skins::full_skin(id)
                .map(|skin| skin.has_frames)
                .unwrap_or(false)
        })
        .cloned();
    let current_species = store
        .pet_value()
        .get("species")
        .and_then(Value::as_str)
        .unwrap_or("gupet")
        .to_string();
    let species = if custom_skin.is_some() {
        "skin".to_string()
    } else if current_species == "skin" {
        "gupet".to_string()
    } else {
        current_species
    };
    store.set_active_skins(valid.clone(), custom_skin, &species)?;
    Ok(valid)
}

fn validated_active_skins(store: &mut SettingsStore) -> Result<Vec<String>, String> {
    let ids = store.active_skins();
    let mut valid = Vec::new();
    for id in &ids {
        if id == BUILTIN_SKIN || skins::find_skin_folder(id).is_some() {
            valid.push(id.clone());
        }
    }
    if valid.is_empty() {
        valid.push(BUILTIN_SKIN.to_string());
    }
    if valid == ids
        && store
            .pet_value()
            .get("activeSkinIds")
            .and_then(Value::as_array)
            .map(|arr| {
                arr.iter()
                    .filter_map(Value::as_str)
                    .map(str::to_string)
                    .collect::<Vec<_>>()
                    == valid
            })
            .unwrap_or(false)
    {
        return Ok(valid);
    }
    change_active_skins(store, valid)
}

fn emit_state(app: &AppHandle) {
    let _ = app.emit("gupet://state", json!({"at": now_ms()}));
}

fn apply_running(app: &AppHandle, running: bool) {
    if let Some(overlay) = app.get_webview_window("overlay") {
        if running {
            let _ = overlay.show();
        } else {
            let _ = overlay.hide();
        }
    }
}

fn settings_window_visible(app: &AppHandle) -> bool {
    app.get_webview_window("settings")
        .and_then(|window| window.is_visible().ok())
        .unwrap_or(false)
}

fn emit_settings_visible(app: &AppHandle, visible: bool) {
    let _ = app.emit("gupet://settings-visible", json!({ "visible": visible }));
}

fn show_settings_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("settings") {
        // Окно настроек — обычное окно. Поверх всех должен оставаться только оверлей.
        let _ = window.set_always_on_top(false);
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
        emit_settings_visible(app, true);
    }
}

fn commit_running(app: &AppHandle, running: bool, notify: bool) -> Result<(), String> {
    {
        let state = app.state::<AppState>();
        let mut store = state
            .store
            .lock()
            .map_err(|_| "Настройки заняты".to_string())?;
        store.set_running(running)?;
    }
    apply_running(app, running);
    emit_state(app);
    if notify {
        let body = if running {
            "Питомец снова гуляет по экрану"
        } else {
            "Питомец спрятан. Открыть настройки можно из трея"
        };
        system::send_notification("GUPet", body);
    }
    Ok(())
}

fn write_debug_report(kind: &str, report: Value) {
    if std::env::var_os("GUPET_DEBUG_STATUS").is_none() {
        return;
    }
    let dir = store::config_dir();
    let path = dir.join("runtime-status.json");
    let _ = fs::create_dir_all(&dir);
    let mut root = fs::read_to_string(&path)
        .ok()
        .and_then(|text| serde_json::from_str::<Value>(&text).ok())
        .and_then(|value| value.as_object().cloned())
        .unwrap_or_default();
    root.insert(kind.to_string(), json!({"at": now_ms(), "data": report}));
    if let Ok(text) = serde_json::to_string_pretty(&Value::Object(root)) {
        let _ = fs::write(path, text);
    }
}

#[derive(Clone, Serialize)]
struct OpenAppInfo {
    id: String,
    label: String,
    terms: Vec<String>,
}

#[cfg(windows)]
fn friendly_app_label(stem: &str) -> String {
    let lower = stem.to_lowercase();
    let known = match lower.as_str() {
        "chrome" => Some("Google Chrome"),
        "msedge" => Some("Microsoft Edge"),
        "firefox" => Some("Mozilla Firefox"),
        "code" => Some("Visual Studio Code"),
        "explorer" => Some("Проводник"),
        "notepad" => Some("Блокнот"),
        "wt" | "windowsterminal" => Some("Windows Terminal"),
        "spotify" => Some("Spotify"),
        "discord" => Some("Discord"),
        "telegram" => Some("Telegram"),
        "steam" => Some("Steam"),
        "gupet" => Some("GUPet"),
        _ => None,
    };
    if let Some(label) = known {
        return label.to_string();
    }
    let mut out = String::new();
    let mut uppercase = true;
    for ch in stem.chars() {
        if ch == '-' || ch == '_' {
            out.push(' ');
            uppercase = true;
        } else if uppercase {
            out.extend(ch.to_uppercase());
            uppercase = false;
        } else {
            out.push(ch);
        }
    }
    if out.is_empty() {
        stem.to_string()
    } else {
        out
    }
}

#[cfg(windows)]
fn process_image_name(pid: u32) -> Option<String> {
    unsafe {
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).ok()?;
        let mut buffer = vec![0u16; 32768];
        let mut length = buffer.len() as u32;
        let result = QueryFullProcessImageNameW(
            handle,
            PROCESS_NAME_WIN32,
            PWSTR(buffer.as_mut_ptr()),
            &mut length,
        );
        let _ = CloseHandle(handle);
        result.ok()?;
        if length == 0 {
            return None;
        }
        Some(String::from_utf16_lossy(&buffer[..length as usize]))
    }
}

#[cfg(windows)]
fn window_title(hwnd: HWND) -> Option<String> {
    unsafe {
        let length = GetWindowTextLengthW(hwnd);
        if length <= 0 {
            return None;
        }
        let mut buffer = vec![0u16; length as usize + 1];
        let copied = GetWindowTextW(hwnd, &mut buffer);
        if copied <= 0 {
            return None;
        }
        let text = String::from_utf16_lossy(&buffer[..copied as usize])
            .trim()
            .to_string();
        if text.is_empty() {
            None
        } else {
            Some(text)
        }
    }
}

#[cfg(windows)]
fn window_class(hwnd: HWND) -> Option<String> {
    unsafe {
        let mut buffer = [0u16; 256];
        let length = GetClassNameW(hwnd, &mut buffer);
        if length <= 0 {
            return None;
        }
        let class = String::from_utf16_lossy(&buffer[..length as usize])
            .trim()
            .to_string();
        if class.is_empty() {
            None
        } else {
            Some(class)
        }
    }
}

#[cfg(windows)]
fn window_is_cloaked(hwnd: HWND) -> bool {
    unsafe {
        let mut cloaked = 0u32;
        let result = DwmGetWindowAttribute(
            hwnd,
            DWMWA_CLOAKED,
            &mut cloaked as *mut u32 as *mut c_void,
            std::mem::size_of::<u32>() as u32,
        );
        result.is_ok() && cloaked != 0
    }
}

#[cfg(windows)]
fn window_app_info(hwnd: HWND, require_title: bool) -> Option<OpenAppInfo> {
    unsafe {
        if hwnd.0.is_null() || !IsWindowVisible(hwnd).as_bool() || window_is_cloaked(hwnd) {
            return None;
        }

        let mut pid = 0u32;
        GetWindowThreadProcessId(hwnd, Some(&mut pid));
        if pid == 0 || pid == GetCurrentProcessId() {
            return None;
        }

        let ex_style = GetWindowLongPtrW(hwnd, GWL_EXSTYLE) as u32;
        if ex_style & WS_EX_TOOLWINDOW.0 != 0 {
            return None;
        }

        let class = window_class(hwnd);
        let class_lower = class.as_deref().unwrap_or_default().trim().to_lowercase();
        // Taskbar, desktop and shell helper windows are not applications the
        // user would ever want to add to the hide-on-open list.
        if matches!(
            class_lower.as_str(),
            "shell_traywnd"
                | "shell_secondarytraywnd"
                | "progman"
                | "workerw"
                | "dv2controlhost"
                | "windows.ui.core.corewindow"
                | "foregroundstaging"
                | "multitaskingviewframe"
                | "tasklistthumbnailwnd"
        ) {
            return None;
        }

        let title = window_title(hwnd);
        if require_title && title.is_none() {
            return None;
        }

        let path = process_image_name(pid);
        let mut terms = BTreeSet::new();
        let (id, label) = if let Some(path) = path.as_deref() {
            let file_name = Path::new(path)
                .file_name()
                .map(|name| name.to_string_lossy().to_lowercase())
                .unwrap_or_default();
            let stem = Path::new(path)
                .file_stem()
                .map(|name| name.to_string_lossy().to_string())
                .unwrap_or_default();
            if stem.is_empty() {
                return None;
            }
            let stem_lower = stem.to_lowercase();
            if !file_name.is_empty() {
                terms.insert(file_name);
            }
            terms.insert(stem_lower.clone());
            if stem_lower == "applicationframehost" {
                // UWP windows all run inside ApplicationFrameHost, so use the
                // visible app title as the stable entry instead of grouping
                // Calculator, Settings, Photos, … into one row.
                if let Some(title) = title.as_deref() {
                    let id = title.chars().take(120).collect::<String>().to_lowercase();
                    let label = title.chars().take(80).collect::<String>();
                    (id, label)
                } else {
                    (stem_lower.clone(), friendly_app_label(&stem))
                }
            } else {
                (stem_lower.clone(), friendly_app_label(&stem))
            }
        } else if let Some(title) = title.as_deref() {
            let id = title.chars().take(120).collect::<String>().to_lowercase();
            let label = title.chars().take(80).collect::<String>();
            (id, label)
        } else {
            return None;
        };

        if id.is_empty() || label.is_empty() {
            return None;
        }
        terms.insert(id.clone());
        terms.insert(label.to_lowercase());
        if let Some(class) = class {
            terms.insert(class.to_lowercase());
        }
        if let Some(title) = title {
            terms.insert(title.chars().take(120).collect::<String>().to_lowercase());
        }
        Some(OpenAppInfo {
            id,
            label,
            terms: terms.into_iter().filter(|term| !term.is_empty()).collect(),
        })
    }
}

#[cfg(windows)]
unsafe extern "system" fn enum_open_window(hwnd: HWND, lparam: LPARAM) -> BOOL {
    let apps = &mut *(lparam.0 as *mut Vec<OpenAppInfo>);
    if let Some(info) = window_app_info(hwnd, true) {
        if let Some(existing) = apps.iter_mut().find(|app| app.id == info.id) {
            for term in info.terms {
                if existing.terms.len() >= 32 {
                    break;
                }
                if !existing.terms.contains(&term) {
                    existing.terms.push(term);
                }
            }
        } else {
            apps.push(info);
        }
    }
    BOOL(1)
}

#[cfg(windows)]
fn open_app_entries() -> Vec<OpenAppInfo> {
    let mut apps: Vec<OpenAppInfo> = Vec::new();
    unsafe {
        let _ = EnumWindows(
            Some(enum_open_window),
            LPARAM(&mut apps as *mut Vec<OpenAppInfo> as isize),
        );
    }
    apps.sort_by(|a, b| a.label.to_lowercase().cmp(&b.label.to_lowercase()));
    apps
}

#[cfg(not(windows))]
fn open_app_entries() -> Vec<OpenAppInfo> {
    Vec::new()
}

#[cfg(windows)]
fn active_app_entry() -> Option<OpenAppInfo> {
    unsafe {
        let hwnd = GetForegroundWindow();
        if hwnd.0.is_null() {
            None
        } else {
            window_app_info(hwnd, false)
        }
    }
}

#[cfg(not(windows))]
fn active_app_entry() -> Option<OpenAppInfo> {
    None
}

#[tauri::command]
fn list_active_apps() -> Vec<OpenAppInfo> {
    active_app_entry().into_iter().collect()
}

#[tauri::command]
fn list_open_apps() -> Vec<OpenAppInfo> {
    open_app_entries()
}

#[cfg(windows)]
fn platform_cursor_position() -> Option<[i32; 2]> {
    unsafe {
        let mut point = POINT::default();
        GetCursorPos(&mut point).ok()?;
        Some([point.x, point.y])
    }
}

#[cfg(not(windows))]
fn platform_cursor_position() -> Option<[i32; 2]> {
    None
}

#[tauri::command]
fn cursor_position() -> Option<[i32; 2]> {
    platform_cursor_position()
}

#[tauri::command]
fn record_poop_cleaned(state: State<'_, AppState>) -> Result<u64, String> {
    let mut store = state
        .store
        .lock()
        .map_err(|_| "Настройки заняты".to_string())?;
    store.add_cleaned_poops(1);
    Ok(store.data.poops_cleaned)
}

#[tauri::command]
fn get_boot_state(app: AppHandle, state: State<'_, AppState>) -> Result<Value, String> {
    let mut store = state
        .store
        .lock()
        .map_err(|_| "Настройки заняты".to_string())?;
    let ids = validated_active_skins(&mut store)?;
    Ok(json!({
        "prefs": store.prefs(),
        "pet": store.pet_for_ui(),
        "phrases": store.phrases(),
        "skins": skins::list(),
        "full": skins::full_skins(&ids),
        "running": store.running(),
        "poopsCleaned": store.data.poops_cleaned,
        "settingsVisible": settings_window_visible(&app),
        "autostart": store::is_autostart_enabled(),
        "configDir": store::config_dir().display().to_string(),
        "skinsDir": store::skins_config_dir().display().to_string(),
        "settingsPath": store.settings_path_string()
    }))
}

#[tauri::command]
fn set_pref(
    app: AppHandle,
    state: State<'_, AppState>,
    key: String,
    value: String,
) -> Result<(), String> {
    {
        let mut store = state
            .store
            .lock()
            .map_err(|_| "Настройки заняты".to_string())?;
        store.set_pref(&key, value)?;
    }
    emit_state(&app);
    Ok(())
}

#[tauri::command]
fn set_pet_settings(
    app: AppHandle,
    state: State<'_, AppState>,
    value: Value,
) -> Result<(), String> {
    {
        let mut store = state
            .store
            .lock()
            .map_err(|_| "Настройки заняты".to_string())?;
        store.set_pet(value)?;
    }
    emit_state(&app);
    Ok(())
}

#[tauri::command]
fn set_phrases(app: AppHandle, state: State<'_, AppState>, value: Value) -> Result<(), String> {
    {
        let mut store = state
            .store
            .lock()
            .map_err(|_| "Настройки заняты".to_string())?;
        store.set_phrases(value)?;
    }
    emit_state(&app);
    Ok(())
}

#[tauri::command]
fn set_running(app: AppHandle, running: bool) -> Result<bool, String> {
    commit_running(&app, running, true)?;
    Ok(running)
}

#[tauri::command]
fn get_runtime_state(app: AppHandle, state: State<'_, AppState>) -> Result<Value, String> {
    let mut store = state
        .store
        .lock()
        .map_err(|_| "Настройки заняты".to_string())?;
    let ids = validated_active_skins(&mut store)?;
    Ok(json!({
        "running": store.running(),
        "settings": store.pet_value(),
        "activeIds": ids.clone(),
        "skins": skins::runtime_skins(&ids),
        "phrases": store.phrases(),
        "music": system::current_music_state(),
        "poopsCleaned": store.data.poops_cleaned,
        "settingsVisible": settings_window_visible(&app)
    }))
}

#[tauri::command]
fn load_skin_frames(state: State<'_, AppState>, id: String) -> Result<Value, String> {
    let _ = state;
    serde_json::to_value(skins::frames_for(&id)?).map_err(|e| e.to_string())
}

#[tauri::command]
fn set_active_skin_ids(
    app: AppHandle,
    state: State<'_, AppState>,
    ids: Vec<String>,
) -> Result<Vec<String>, String> {
    let result = {
        let mut store = state
            .store
            .lock()
            .map_err(|_| "Настройки заняты".to_string())?;
        change_active_skins(&mut store, ids)?
    };
    emit_state(&app);
    Ok(result)
}

#[tauri::command]
fn select_skin(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
) -> Result<Vec<String>, String> {
    let result = {
        let mut store = state
            .store
            .lock()
            .map_err(|_| "Настройки заняты".to_string())?;
        change_active_skins(&mut store, vec![id])?
    };
    emit_state(&app);
    Ok(result)
}

#[tauri::command]
fn import_skin_base64(app: AppHandle, filename: String, data: String) -> Result<Value, String> {
    let info = skins::import_base64(&filename, &data)?;
    let _ = system::send_notification("GUPet", &format!("Скин «{}» добавлен в галерею", info.name));
    emit_state(&app);
    serde_json::to_value(info).map_err(|e| e.to_string())
}

#[tauri::command]
fn export_skin(app: AppHandle, id: String) -> Result<String, String> {
    let path = skins::export(&id)?;
    emit_state(&app);
    Ok(path.display().to_string())
}

#[tauri::command]
fn create_skin(
    app: AppHandle,
    state: State<'_, AppState>,
    name: String,
    author: String,
) -> Result<Value, String> {
    let result = (|| -> Result<String, String> {
        let (settings, ids) = {
            let mut store = state
                .store
                .lock()
                .map_err(|_| "Настройки заняты".to_string())?;
            let mut settings = store.pet_value();
            if let Some(obj) = settings.as_object_mut() {
                obj.insert("phrases".to_string(), store.phrases());
            }
            (settings, validated_active_skins(&mut store)?)
        };
        let id = skins::create(&name, &author, settings)?;
        let mut next = ids;
        if !next.contains(&id) {
            next.push(id.clone());
        }
        let mut store = state
            .store
            .lock()
            .map_err(|_| "Настройки заняты".to_string())?;
        change_active_skins(&mut store, next)?;
        Ok(id)
    })();
    emit_state(&app);
    match result {
        Ok(id) => Ok(serde_json::to_value(CreatedSkin {
            ok: true,
            id: Some(id),
            error: None,
        })
        .map_err(|e| e.to_string())?),
        Err(err) => Ok(serde_json::to_value(CreatedSkin {
            ok: false,
            id: None,
            error: Some(err),
        })
        .map_err(|e| e.to_string())?),
    }
}

#[tauri::command]
fn duplicate_skin(
    app: AppHandle,
    state: State<'_, AppState>,
    source_id: String,
) -> Result<Option<String>, String> {
    let (settings, phrases) = {
        let store = state
            .store
            .lock()
            .map_err(|_| "Настройки заняты".to_string())?;
        (store.pet_value(), store.phrases())
    };
    let id = skins::duplicate(&source_id, settings, phrases)?;
    emit_state(&app);
    Ok(Some(id))
}

#[tauri::command]
fn delete_skin(app: AppHandle, state: State<'_, AppState>, id: String) -> Result<bool, String> {
    let ok = skins::delete(&id)?;
    if ok {
        let mut store = state
            .store
            .lock()
            .map_err(|_| "Настройки заняты".to_string())?;
        let ids: Vec<String> = store
            .active_skins()
            .into_iter()
            .filter(|active| active != &id)
            .collect();
        change_active_skins(&mut store, ids)?;
    }
    emit_state(&app);
    Ok(ok)
}

#[tauri::command]
fn save_skin_meta(
    app: AppHandle,
    id: String,
    name: String,
    author: String,
) -> Result<bool, String> {
    let ok = skins::save_meta(&id, &name, &author)?;
    emit_state(&app);
    Ok(ok)
}

#[tauri::command]
fn set_skin_settings(app: AppHandle, id: String, value: Value) -> Result<bool, String> {
    let ok = skins::set_settings(&id, value)?;
    emit_state(&app);
    Ok(ok)
}

#[tauri::command]
fn set_pc_settings(app: AppHandle, id: String, value: Value) -> Result<bool, String> {
    let ok = skins::set_pc_settings(&id, value)?;
    emit_state(&app);
    Ok(ok)
}

#[tauri::command]
fn get_pc_settings(id: String) -> Result<Value, String> {
    Ok(skins::pc_settings_for(&id).unwrap_or_else(skins::default_pc_settings))
}

#[tauri::command]
fn get_skin_settings(state: State<'_, AppState>, id: String) -> Result<Value, String> {
    if id == BUILTIN_SKIN {
        let store = state
            .store
            .lock()
            .map_err(|_| "Настройки заняты".to_string())?;
        let mut value = store.pet_value();
        if let Some(obj) = value.as_object_mut() {
            obj.insert("phrases".to_string(), store.phrases());
        }
        return Ok(value);
    }
    Ok(skins::settings_for(&id).unwrap_or_else(|| json!({})))
}

#[tauri::command]
fn snapshot_skin(app: AppHandle, state: State<'_, AppState>, id: String) -> Result<bool, String> {
    let phrases = {
        let store = state
            .store
            .lock()
            .map_err(|_| "Настройки заняты".to_string())?;
        store.phrases()
    };
    let ok = skins::snapshot_phrases(&id, phrases)?;
    emit_state(&app);
    Ok(ok)
}

#[tauri::command]
fn set_frame_base64(
    app: AppHandle,
    id: String,
    frame: String,
    data: String,
) -> Result<bool, String> {
    let ok = skins::write_frame_base64(&id, &frame, &data)?;
    emit_state(&app);
    Ok(ok)
}

#[tauri::command]
fn delete_frame(app: AppHandle, id: String, frame: String) -> Result<bool, String> {
    let ok = skins::delete_frame(&id, &frame)?;
    emit_state(&app);
    Ok(ok)
}

#[tauri::command]
fn get_frame_info(id: String, frame: String) -> Result<Value, String> {
    let full = skins::full_skin(&id).ok_or_else(|| "Скин не найден".to_string())?;
    let info = full.frames.get(&frame);
    Ok(match info {
        Some(info) => json!({"exists": info.exists, "fileName": info.file_name}),
        None => json!({"exists": false, "fileName": ""}),
    })
}

#[tauri::command]
fn get_skin(id: String) -> Result<Value, String> {
    let full = skins::full_skin(&id).ok_or_else(|| "Скин не найден".to_string())?;
    serde_json::to_value(full).map_err(|e| e.to_string())
}

#[tauri::command]
fn set_last_position(state: State<'_, AppState>, x: f64, y: f64) -> Result<(), String> {
    let mut store = state
        .store
        .lock()
        .map_err(|_| "Настройки заняты".to_string())?;
    let Some(obj) = store.data.pet.as_object_mut() else {
        return Ok(());
    };
    obj.insert("lastPosX".to_string(), json!(x));
    obj.insert("lastPosY".to_string(), json!(y));
    store.save()
}

#[tauri::command]
fn emit_poop(app: AppHandle) -> Result<(), String> {
    let _ = app.emit("gupet://poop-now", json!({"manual": true}));
    Ok(())
}

#[tauri::command]
fn clean_poops(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    {
        let mut store = state
            .store
            .lock()
            .map_err(|_| "Настройки заняты".to_string())?;
        store.add_cleaned_poops(1);
    }
    let _ = app.emit("gupet://clean-poops", json!({"manual": true}));
    Ok(())
}

#[tauri::command]
fn test_phrase(app: AppHandle, text: String) -> Result<(), String> {
    let _ = app.emit("gupet://test-phrase", json!({"text": text}));
    Ok(())
}

#[tauri::command]
fn recenter_pet(app: AppHandle) -> Result<(), String> {
    let _ = app.emit("gupet://recenter", json!({}));
    Ok(())
}

#[tauri::command]
fn set_autostart(enabled: bool) -> Result<bool, String> {
    store::set_autostart(enabled)
}

#[tauri::command]
fn get_autostart() -> bool {
    store::is_autostart_enabled()
}

#[tauri::command]
fn send_test_notification(title: Option<String>, body: Option<String>) -> bool {
    system::send_notification(
        title.as_deref().unwrap_or("GUPet"),
        body.as_deref().unwrap_or("Уведомления работают"),
    )
}

#[tauri::command]
fn open_url(url: String) -> bool {
    system::open_target(&url, true)
}

#[tauri::command]
fn open_skins_folder() -> bool {
    let folder = store::skins_config_dir();
    let _ = fs::create_dir_all(&folder);
    system::open_target(&folder.display().to_string(), false)
}

#[tauri::command]
fn show_settings(app: AppHandle) {
    show_settings_window(&app);
}

#[tauri::command]
fn quit_app(app: AppHandle) {
    app.exit(0);
}

#[tauri::command]
fn ui_ready(report: Option<Value>) {
    write_debug_report("settings", report.unwrap_or(Value::Null));
    eprintln!("[gupet] settings UI ready");
}

#[tauri::command]
fn overlay_status(report: Value) {
    write_debug_report("overlay", report);
}

#[cfg(windows)]
static CLICK_REGIONS: Mutex<Vec<[i32; 4]>> = Mutex::new(Vec::new());
#[cfg(windows)]
static OVERLAY_HWND: AtomicIsize = AtomicIsize::new(0);
#[cfg(windows)]
static OVERLAY_LAST_STYLE: AtomicIsize = AtomicIsize::new(isize::MIN);
#[cfg(windows)]
static CLICK_WATCHER_STARTED: AtomicBool = AtomicBool::new(false);

#[cfg(windows)]
fn window_hwnd<R: tauri::Runtime>(window: &tauri::WebviewWindow<R>) -> Option<isize> {
    let handle = window.window_handle().ok()?;
    match handle.as_raw() {
        RawWindowHandle::Win32(handle) => Some(handle.hwnd.get()),
        _ => None,
    }
}

#[cfg(windows)]
fn overlay_is_click_through() -> bool {
    let hwnd_raw = OVERLAY_HWND.load(Ordering::SeqCst);
    if hwnd_raw == 0 {
        return true;
    }
    let hwnd = HWND(hwnd_raw as *mut c_void);
    unsafe {
        let mut cursor = POINT::default();
        if GetCursorPos(&mut cursor).is_err() {
            return true;
        }
        let mut origin = POINT::default();
        if !ClientToScreen(hwnd, &mut origin).as_bool() {
            return true;
        }
        let x = cursor.x - origin.x;
        let y = cursor.y - origin.y;
        let regions = CLICK_REGIONS
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        !regions.iter().any(|rect| {
            x >= rect[0]
                && y >= rect[1]
                && x < rect[0].saturating_add(rect[2])
                && y < rect[1].saturating_add(rect[3])
        })
    }
}

#[cfg(windows)]
fn update_overlay_click_through() {
    let hwnd_raw = OVERLAY_HWND.load(Ordering::SeqCst);
    if hwnd_raw == 0 {
        return;
    }
    let hwnd = HWND(hwnd_raw as *mut c_void);
    unsafe {
        let mut style = GetWindowLongPtrW(hwnd, GWL_EXSTYLE) as u32;
        style |= WS_EX_LAYERED.0 | WS_EX_NOACTIVATE.0 | WS_EX_TOOLWINDOW.0;
        if overlay_is_click_through() {
            style |= WS_EX_TRANSPARENT.0;
        } else {
            style &= !WS_EX_TRANSPARENT.0;
        }
        let style = style as isize;
        if OVERLAY_LAST_STYLE.swap(style, Ordering::SeqCst) == style {
            return;
        }
        SetWindowLongPtrW(hwnd, GWL_EXSTYLE, style);
        let _ = SetWindowPos(
            hwnd,
            HWND::default(),
            0,
            0,
            0,
            0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED,
        );
    }
}

#[cfg(windows)]
fn start_overlay_click_watcher() {
    if CLICK_WATCHER_STARTED.swap(true, Ordering::SeqCst) {
        return;
    }
    std::thread::spawn(|| loop {
        update_overlay_click_through();
        std::thread::sleep(Duration::from_millis(33));
    });
}

#[cfg(windows)]
fn prepare_overlay_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("overlay") {
        if let Some(hwnd) = window_hwnd(&window) {
            OVERLAY_HWND.store(hwnd, Ordering::SeqCst);
            OVERLAY_LAST_STYLE.store(isize::MIN, Ordering::SeqCst);
            update_overlay_click_through();
            start_overlay_click_watcher();
        }
    }
}

#[tauri::command]
#[cfg(windows)]
fn set_click_regions(app: AppHandle, rects: Vec<[i32; 4]>) -> Result<bool, String> {
    let window = app
        .get_webview_window("overlay")
        .ok_or_else(|| "Окно оверлея не найдено".to_string())?;
    let hwnd = window_hwnd(&window).ok_or_else(|| "Окно оверлея недоступно".to_string())?;
    if hwnd == 0 {
        return Ok(false);
    }
    {
        let mut regions = CLICK_REGIONS
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        *regions = rects;
    }
    OVERLAY_HWND.store(hwnd, Ordering::SeqCst);
    OVERLAY_LAST_STYLE.store(isize::MIN, Ordering::SeqCst);
    update_overlay_click_through();
    start_overlay_click_watcher();
    Ok(true)
}

#[tauri::command]
#[cfg(not(windows))]
fn set_click_regions(_app: AppHandle, _rects: Vec<[i32; 4]>) -> Result<bool, String> {
    Ok(true)
}

fn toggle_running(app: &AppHandle) {
    let running = {
        let state = app.state::<AppState>();
        let store = match state.store.lock() {
            Ok(store) => store,
            Err(_) => return,
        };
        !store.running()
    };
    let _ = commit_running(app, running, true);
}

fn build_menu<R: tauri::Runtime>(app: &tauri::App<R>) -> tauri::Result<Menu<R>> {
    let settings = MenuItem::with_id(app, "settings", "Настройки", true, None::<&str>)?;
    let toggle = MenuItem::with_id(
        app,
        "toggle",
        "Показать / скрыть питомца",
        true,
        None::<&str>,
    )?;
    let poop = MenuItem::with_id(app, "poop", "Позвать кучку", true, None::<&str>)?;
    let clean = MenuItem::with_id(app, "clean", "Очистить кучки", true, None::<&str>)?;
    let recenter = MenuItem::with_id(app, "recenter", "Вернуть на место", true, None::<&str>)?;
    let sep = PredefinedMenuItem::separator(app)?;
    let quit = MenuItem::with_id(app, "quit", "Выход", true, None::<&str>)?;
    Menu::with_items(
        app,
        &[&settings, &toggle, &poop, &clean, &recenter, &sep, &quit],
    )
}

fn main() {
    system::init_platform();

    let autostart_launch = std::env::args().any(|arg| arg == "--autostart");

    tauri::Builder::default()
        .manage(AppState {
            store: Mutex::new(SettingsStore::load()),
        })
        .invoke_handler(tauri::generate_handler![
            get_boot_state,
            set_pref,
            set_pet_settings,
            set_phrases,
            set_running,
            get_runtime_state,
            load_skin_frames,
            set_active_skin_ids,
            select_skin,
            import_skin_base64,
            export_skin,
            create_skin,
            duplicate_skin,
            delete_skin,
            save_skin_meta,
            set_skin_settings,
            set_pc_settings,
            get_pc_settings,
            get_skin_settings,
            snapshot_skin,
            set_frame_base64,
            delete_frame,
            get_frame_info,
            get_skin,
            set_last_position,
            emit_poop,
            clean_poops,
            record_poop_cleaned,
            list_open_apps,
            list_active_apps,
            cursor_position,
            test_phrase,
            recenter_pet,
            set_autostart,
            get_autostart,
            send_test_notification,
            open_url,
            open_skins_folder,
            show_settings,
            quit_app,
            ui_ready,
            overlay_status,
            set_click_regions
        ])
        .setup(move |app| {
            let running = {
                let state = app.state::<AppState>();
                let store = state
                    .store
                    .lock()
                    .map_err(|_| std::io::Error::other("Настройки заняты"))?;
                store.running()
            };

            let settings_window =
                WebviewWindowBuilder::new(app, "settings", WebviewUrl::App("index.html".into()))
                    .title("GUPet")
                    .inner_size(1040.0, 820.0)
                    .min_inner_size(720.0, 560.0)
                    .center()
                    .resizable(true)
                    .always_on_top(false)
                    .visible(!autostart_launch)
                    .build()?;
            settings_window.set_icon(app.default_window_icon().cloned().unwrap_or_else(|| {
                tauri::image::Image::from_bytes(include_bytes!("../icons/32x32.png")).expect("icon")
            }))?;

            let overlay =
                WebviewWindowBuilder::new(app, "overlay", WebviewUrl::App("overlay.html".into()))
                    .title("GUPet Overlay")
                    .transparent(true)
                    .decorations(false)
                    .always_on_top(true)
                    .skip_taskbar(true)
                    .visible_on_all_workspaces(true)
                    .resizable(false)
                    .maximizable(false)
                    .minimizable(false)
                    .closable(false)
                    .focusable(false)
                    .focused(false)
                    .visible(false)
                    .shadow(false)
                    .fullscreen(true)
                    .background_color(tauri::window::Color(0, 0, 0, 0))
                    .build()?;
            #[cfg(windows)]
            prepare_overlay_window(app.handle());
            if running {
                let _ = overlay.show();
            }

            if let Ok(menu) = build_menu(app) {
                let icon = tauri::image::Image::from_bytes(include_bytes!("../icons/32x32.png"))?;
                let _ = TrayIconBuilder::with_id("main-tray")
                    .icon(icon)
                    .tooltip("GUPet — пиксельный питомец")
                    .menu(&menu)
                    .show_menu_on_left_click(false)
                    .on_menu_event(|app, event| match event.id().as_ref() {
                        "settings" => show_settings_window(app),
                        "toggle" => toggle_running(app),
                        "poop" => {
                            let _ = app.emit("gupet://poop-now", json!({"manual": true}));
                        }
                        "clean" => {
                            let _ = app.emit("gupet://clean-poops", json!({"manual": true}));
                        }
                        "recenter" => {
                            let _ = app.emit("gupet://recenter", json!({}));
                        }
                        "quit" => app.exit(0),
                        _ => {}
                    })
                    .on_tray_icon_event(|tray, event| {
                        if let TrayIconEvent::Click {
                            button: MouseButton::Left,
                            button_state: MouseButtonState::Up,
                            ..
                        } = event
                        {
                            show_settings_window(tray.app_handle());
                        }
                    })
                    .build(app)?;
            } else {
                eprintln!("[gupet] tray menu unavailable, continuing without tray");
            }

            system::start_music_monitor(app.handle().clone());
            system::start_notification_monitor(app.handle().clone());

            if std::env::var_os("GUPET_DEBUG_STATUS").is_some() {
                eprintln!(
                    "[gupet] settings={} skins={}",
                    store::settings_path().display(),
                    store::skins_config_dir().display()
                );
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if window.label() != "settings" {
                if let WindowEvent::CloseRequested { api, .. } = event {
                    if window.label() == "overlay" {
                        api.prevent_close();
                        let _ = window.hide();
                    }
                }
                return;
            }
            match event {
                WindowEvent::CloseRequested { api, .. } => {
                    api.prevent_close();
                    let _ = window.hide();
                    emit_settings_visible(window.app_handle(), false);
                }
                WindowEvent::Focused(_) => {
                    let app = window.app_handle();
                    emit_settings_visible(app, settings_window_visible(app));
                }
                WindowEvent::Resized(_) | WindowEvent::Moved(_) => {
                    let app = window.app_handle();
                    emit_settings_visible(app, settings_window_visible(app));
                }
                _ => {}
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running GUPet");
}
