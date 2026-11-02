#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
#![recursion_limit = "512"]

mod market;
mod skins;
mod store;
mod system;

use raw_window_handle::{HasWindowHandle, RawWindowHandle};
use serde::Serialize;
use serde_json::{json, Value};
#[cfg(target_os = "linux")]
use std::collections::BTreeSet;
#[cfg(target_os = "linux")]
use std::ffi::{CStr, CString};
use std::fs;
#[cfg(target_os = "linux")]
use std::os::raw::{c_char, c_int, c_uchar, c_ulong, c_void};
#[cfg(target_os = "linux")]
use std::ptr;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};
use store::{SettingsStore, BUILTIN_SKIN};
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder, WindowEvent};

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

#[cfg(target_os = "linux")]
fn x11_window_app(
    display: *mut x11::xlib::Display,
    window: x11::xlib::Window,
) -> Option<OpenAppInfo> {
    unsafe {
        let mut instance = None;
        let mut class = None;
        let mut hint = x11::xlib::XClassHint {
            res_name: ptr::null_mut(),
            res_class: ptr::null_mut(),
        };
        if x11::xlib::XGetClassHint(display, window, &mut hint) != 0 {
            if !hint.res_name.is_null() {
                if let Ok(name) = CStr::from_ptr(hint.res_name).to_str() {
                    instance = Some(name.trim().to_string());
                }
                x11::xlib::XFree(hint.res_name as *mut c_void);
            }
            if !hint.res_class.is_null() {
                if let Ok(name) = CStr::from_ptr(hint.res_class).to_str() {
                    class = Some(name.trim().to_string());
                }
                x11::xlib::XFree(hint.res_class as *mut c_void);
            }
        }
        let mut title = None;
        let mut title_ptr: *mut c_char = ptr::null_mut();
        if x11::xlib::XFetchName(display, window, &mut title_ptr) != 0 && !title_ptr.is_null() {
            if let Ok(name) = CStr::from_ptr(title_ptr).to_str() {
                title = Some(name.trim().to_string());
            }
            x11::xlib::XFree(title_ptr as *mut c_void);
        }

        // Фильтруем собственные окна GUPet и системные XWayland-мосты только
        // по WM_CLASS/WM_INSTANCE. Заголовок окна может содержать слово
        // "GUPet" у совершенно постороннего приложения (например, Konsole),
        // и по нему нельзя отбрасывать активное окно.
        let mut identity_terms = BTreeSet::new();
        for raw in [instance.as_deref(), class.as_deref()].into_iter().flatten() {
            let term = raw.trim().to_lowercase();
            if !term.is_empty() && term.chars().count() <= 120 {
                identity_terms.insert(term);
            }
        }
        if identity_terms.iter().any(|term| {
            term.contains("gupet")
                || term.contains("xwaylandvideobridge")
                || term.contains("mutter-x11-frames")
                || term.contains("wayland to x recording bridge")
        }) {
            return None;
        }

        let mut terms = identity_terms;
        if terms.is_empty() {
            if let Some(raw) = title.as_deref() {
                let term = raw.trim().to_lowercase();
                if !term.is_empty() && term.chars().count() <= 120 {
                    terms.insert(term);
                }
            }
        }
        if terms.is_empty() {
            return None;
        }

        let raw_label = class
            .as_deref()
            .or(instance.as_deref())
            .or(title.as_deref())?
            .trim();
        if raw_label.is_empty() {
            return None;
        }
        let id: String = raw_label
            .chars()
            .take(120)
            .collect::<String>()
            .to_lowercase();
        let friendly = raw_label.replace('-', " ");
        let label: String = friendly.chars().take(80).collect();
        if id.is_empty() || label.is_empty() {
            return None;
        }
        Some(OpenAppInfo {
            id,
            label,
            terms: terms.into_iter().collect(),
        })
    }
}

#[cfg(target_os = "linux")]
fn open_app_entries() -> Vec<OpenAppInfo> {
    unsafe {
        let display = x11::xlib::XOpenDisplay(ptr::null());
        if display.is_null() {
            return Vec::new();
        }
        let root = x11::xlib::XDefaultRootWindow(display);
        let client_list_name = CString::new("_NET_CLIENT_LIST").unwrap();
        let atom = x11::xlib::XInternAtom(display, client_list_name.as_ptr(), 0);
        let mut actual_type: x11::xlib::Atom = 0;
        let mut actual_format: c_int = 0;
        let mut nitems: c_ulong = 0;
        let mut bytes_after: c_ulong = 0;
        let mut prop: *mut c_uchar = ptr::null_mut();
        let status = x11::xlib::XGetWindowProperty(
            display,
            root,
            atom,
            0,
            4096,
            0,
            x11::xlib::XA_WINDOW,
            &mut actual_type,
            &mut actual_format,
            &mut nitems,
            &mut bytes_after,
            &mut prop,
        );
        let mut apps: Vec<OpenAppInfo> = Vec::new();
        if status == x11::xlib::Success as c_int
            && !prop.is_null()
            && actual_format == 32
            && nitems > 0
        {
            let windows =
                std::slice::from_raw_parts(prop as *const x11::xlib::Window, nitems as usize);
            for window in windows {
                if let Some(info) = x11_window_app(display, *window) {
                    if let Some(existing) = apps.iter_mut().find(|app| app.id == info.id) {
                        for term in info.terms {
                            if !existing.terms.contains(&term) {
                                existing.terms.push(term);
                            }
                        }
                    } else {
                        apps.push(info);
                    }
                }
            }
        }
        if !prop.is_null() {
            x11::xlib::XFree(prop as *mut c_void);
        }
        x11::xlib::XCloseDisplay(display);
        apps.sort_by(|a, b| a.label.to_lowercase().cmp(&b.label.to_lowercase()));
        apps
    }
}

#[cfg(not(target_os = "linux"))]
fn open_app_entries() -> Vec<OpenAppInfo> {
    Vec::new()
}

#[cfg(target_os = "linux")]
fn active_app_entry() -> Option<OpenAppInfo> {
    unsafe {
        let display = x11::xlib::XOpenDisplay(ptr::null());
        if display.is_null() {
            return None;
        }
        let root = x11::xlib::XDefaultRootWindow(display);
        let active_name = CString::new("_NET_ACTIVE_WINDOW").unwrap();
        let atom = x11::xlib::XInternAtom(display, active_name.as_ptr(), 0);
        let mut actual_type: x11::xlib::Atom = 0;
        let mut actual_format: c_int = 0;
        let mut nitems: c_ulong = 0;
        let mut bytes_after: c_ulong = 0;
        let mut prop: *mut c_uchar = ptr::null_mut();
        let status = x11::xlib::XGetWindowProperty(
            display,
            root,
            atom,
            0,
            1,
            0,
            x11::xlib::XA_WINDOW,
            &mut actual_type,
            &mut actual_format,
            &mut nitems,
            &mut bytes_after,
            &mut prop,
        );
        let mut active = None;
        if status == x11::xlib::Success as c_int
            && !prop.is_null()
            && actual_format == 32
            && nitems > 0
        {
            let window = std::slice::from_raw_parts(
                prop as *const x11::xlib::Window,
                nitems as usize,
            )[0];
            if window != 0 {
                active = x11_window_app(display, window);
            }
        }
        if !prop.is_null() {
            x11::xlib::XFree(prop as *mut c_void);
        }
        x11::xlib::XCloseDisplay(display);
        active
    }
}

#[cfg(target_os = "linux")]
#[tauri::command]
fn list_active_apps() -> Vec<OpenAppInfo> {
    active_app_entry().into_iter().collect()
}

#[cfg(not(target_os = "linux"))]
#[tauri::command]
fn list_active_apps() -> Vec<OpenAppInfo> {
    Vec::new()
}

#[tauri::command]
fn list_open_apps() -> Vec<OpenAppInfo> {
    open_app_entries()
}

#[cfg(target_os = "linux")]
fn x11_cursor_position() -> Option<[i32; 2]> {
    unsafe {
        let display = x11::xlib::XOpenDisplay(ptr::null());
        if display.is_null() {
            return None;
        }
        let root = x11::xlib::XDefaultRootWindow(display);
        let mut root_return: x11::xlib::Window = 0;
        let mut child_return: x11::xlib::Window = 0;
        let mut root_x: c_int = 0;
        let mut root_y: c_int = 0;
        let mut win_x: c_int = 0;
        let mut win_y: c_int = 0;
        let mut mask: u32 = 0;
        let ok = x11::xlib::XQueryPointer(
            display,
            root,
            &mut root_return,
            &mut child_return,
            &mut root_x,
            &mut root_y,
            &mut win_x,
            &mut win_y,
            &mut mask,
        );
        x11::xlib::XCloseDisplay(display);
        if ok != 0 {
            Some([root_x, root_y])
        } else {
            None
        }
    }
}

#[cfg(not(target_os = "linux"))]
fn x11_cursor_position() -> Option<[i32; 2]> {
    None
}

#[tauri::command]
fn cursor_position() -> Option<[i32; 2]> {
    x11_cursor_position()
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
async fn fetch_remote_skins() -> Result<Vec<market::RemoteSkin>, String> {
    let result = tauri::async_runtime::spawn_blocking(market::fetch_remote_skins)
        .await
        .map_err(|err| format!("Не удалось выполнить запрос к GitHub: {err}"))?;
    if std::env::var_os("GUPET_DEBUG_STATUS").is_some() {
        match &result {
            Ok(skins) => eprintln!("[gupet] market: loaded {} skins", skins.len()),
            Err(err) => eprintln!("[gupet] market: {err}"),
        }
    }
    result
}

#[tauri::command]
async fn download_remote_skin(
    app: AppHandle,
    url: String,
    filename: Option<String>,
) -> Result<Value, String> {
    // Сеть и распаковка архива — в blocking-пуле, чтобы не подвешивать UI.
    let (info, bytes) = tauri::async_runtime::spawn_blocking(move || {
        let bytes = market::download_skin(&url)?;
        let info =
            skins::import_bytes(filename.as_deref().unwrap_or("skin.gupet"), &bytes)?;
        Ok::<(skins::SkinInfo, Vec<u8>), String>((info, bytes))
    })
    .await
    .map_err(|err| format!("Не удалось скачать скин: {err}"))??;
    let state = app.state::<AppState>();
    let active = {
        let mut store = state
            .store
            .lock()
            .map_err(|_| "Настройки заняты".to_string())?;
        change_active_skins(&mut store, vec![info.id.clone()])?
    };
    // Рядом с распакованным скином сохраняем исходный архив .gupet,
    // чтобы его можно было найти в ~/.config/gupet/skins/ и передать другому.
    let archive = store::skins_config_dir().join(format!("{}.gupet", info.id));
    let archive_path = if fs::create_dir_all(store::skins_config_dir()).is_ok()
        && fs::write(&archive, &bytes).is_ok()
    {
        Some(archive.display().to_string())
    } else {
        None
    };
    let _ = system::send_notification(
        "GUPet",
        &format!("Скин «{}» скачан из магазина", info.name),
    );
    emit_state(&app);
    let mut value = serde_json::to_value(&info).map_err(|e| e.to_string())?;
    if let Some(obj) = value.as_object_mut() {
        obj.insert("activeIds".to_string(), json!(active));
        obj.insert("archivePath".to_string(), json!(archive_path));
    }
    Ok(value)
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

#[cfg(target_os = "linux")]
#[link(name = "Xext")]
extern "C" {
    fn XShapeCombineRectangles(
        display: *mut x11::xlib::Display,
        dest: x11::xlib::Window,
        dest_kind: i32,
        x_offset: i32,
        y_offset: i32,
        rectangles: *mut x11::xlib::XRectangle,
        count: i32,
        operation: i32,
        ordering: i32,
    ) -> i32;
}

#[tauri::command]
#[cfg(target_os = "linux")]
fn set_click_regions(app: AppHandle, rects: Vec<[i32; 4]>) -> Result<bool, String> {
    let window = app
        .get_webview_window("overlay")
        .ok_or_else(|| "Окно оверлея не найдено".to_string())?;
    let handle = window
        .window_handle()
        .map_err(|e| format!("Окно оверлея недоступно: {e}"))?;
    let xid = match handle.as_raw() {
        RawWindowHandle::Xlib(handle) => handle.window,
        RawWindowHandle::Xcb(handle) => handle.window.get() as u64,
        _ => return Ok(false),
    };
    if xid == 0 {
        return Ok(false);
    }
    const SHAPE_INPUT: i32 = 2;
    const SHAPE_SET: i32 = 0;
    const UNSORTED: i32 = 0;
    let mut rectangles = Vec::with_capacity(rects.len());
    for rect in rects {
        let x = rect[0].clamp(i16::MIN as i32, i16::MAX as i32) as i16;
        let y = rect[1].clamp(i16::MIN as i32, i16::MAX as i32) as i16;
        let width = rect[2].max(0).min(u16::MAX as i32) as u16;
        let height = rect[3].max(0).min(u16::MAX as i32) as u16;
        rectangles.push(x11::xlib::XRectangle {
            x,
            y,
            width,
            height,
        });
    }
    unsafe {
        let display = x11::xlib::XOpenDisplay(std::ptr::null());
        if display.is_null() {
            return Err("Не удалось открыть X11 display".to_string());
        }
        XShapeCombineRectangles(
            display,
            xid,
            SHAPE_INPUT,
            0,
            0,
            rectangles.as_mut_ptr(),
            rectangles.len() as i32,
            SHAPE_SET,
            UNSORTED,
        );
        x11::xlib::XFlush(display);
        x11::xlib::XCloseDisplay(display);
    }
    Ok(true)
}

#[tauri::command]
#[cfg(not(target_os = "linux"))]
fn set_click_regions(_app: AppHandle, _rects: Vec<[i32; 4]>) -> Result<bool, String> {
    Ok(false)
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
    if std::env::var_os("DISPLAY").is_some() && std::env::var_os("GUPET_NATIVE_WAYLAND").is_none() {
        std::env::set_var("GDK_BACKEND", "x11");
    }

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
            fetch_remote_skins,
            download_remote_skin,
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
