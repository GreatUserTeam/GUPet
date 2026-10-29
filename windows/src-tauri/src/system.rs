use serde::Serialize;
#[cfg(windows)]
use serde_json::json;
#[cfg(windows)]
use std::cell::Cell;
#[cfg(windows)]
use std::collections::HashSet;
use std::sync::atomic::{AtomicU64, Ordering};
#[cfg(windows)]
use std::thread;
#[cfg(windows)]
use std::time::Duration;
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::AppHandle;
#[cfg(windows)]
use tauri::Emitter;
#[cfg(windows)]
use windows::core::{HSTRING, PCWSTR};
#[cfg(windows)]
use windows::Data::Xml::Dom::XmlDocument;
#[cfg(windows)]
use windows::Media::Control::{
    GlobalSystemMediaTransportControlsSessionManager,
    GlobalSystemMediaTransportControlsSessionPlaybackStatus,
};
#[cfg(windows)]
use windows::Win32::Foundation::HWND;
#[cfg(windows)]
use windows::Win32::System::Com::{CoInitializeEx, COINIT_MULTITHREADED};
#[cfg(windows)]
use windows::Win32::UI::Shell::{SetCurrentProcessExplicitAppUserModelID, ShellExecuteW};
#[cfg(windows)]
use windows::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;
#[cfg(windows)]
use windows::UI::Notifications::Management::{
    UserNotificationListener, UserNotificationListenerAccessStatus,
};
#[cfg(windows)]
use windows::UI::Notifications::{NotificationKinds, ToastNotification, ToastNotificationManager};

#[cfg(windows)]
const APP_ID: &str = "net.guteam.gupet.windows";
static OWN_NOTIFICATIONS_UNTIL_MS: AtomicU64 = AtomicU64::new(0);

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[derive(Clone, Debug, Default, Serialize)]
pub struct MusicState {
    pub playing: bool,
    pub track: String,
    pub artist: String,
    pub player: String,
}

/// Sets the AppUserModelID early, before any window is created. Windows uses
/// it for toast notifications and for grouping taskbar windows.
pub fn init_platform() {
    #[cfg(windows)]
    {
        let app_id = wide_null(APP_ID);
        unsafe {
            let _ = SetCurrentProcessExplicitAppUserModelID(PCWSTR(app_id.as_ptr()));
        }
    }
}

#[cfg(windows)]
fn wide_null(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(std::iter::once(0)).collect()
}

#[cfg(windows)]
thread_local! {
    static WINRT_READY: Cell<bool> = const { Cell::new(false) };
}

#[cfg(windows)]
fn ensure_winrt() {
    let _ = WINRT_READY.try_with(|ready| {
        if !ready.get() {
            // Tauri may already have initialized COM on this thread. A
            // RPC_E_CHANGED_MODE result is harmless: WinRT is still usable.
            unsafe {
                let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            }
            ready.set(true);
        }
    });
}

pub fn send_notification(title: &str, body: &str) -> bool {
    // Notification polling is not instantaneous. Suppress our own toasts for
    // a few seconds so the pet never reacts to notifications it created.
    OWN_NOTIFICATIONS_UNTIL_MS.store(now_ms().saturating_add(5000), Ordering::Relaxed);
    #[cfg(windows)]
    {
        send_windows_toast(title, body)
    }
    #[cfg(not(windows))]
    {
        let _ = (title, body);
        false
    }
}

#[cfg(windows)]
fn send_windows_toast(title: &str, body: &str) -> bool {
    ensure_winrt();
    let xml = format!(
        "<toast><visual><binding template=\"ToastGeneric\"><text>{}</text><text>{}</text></binding></visual></toast>",
        escape_xml(title),
        escape_xml(body)
    );
    let document = match XmlDocument::new() {
        Ok(document) => document,
        Err(_) => return false,
    };
    if document.LoadXml(&HSTRING::from(xml.as_str())).is_err() {
        return false;
    }
    let toast = match ToastNotification::CreateToastNotification(&document) {
        Ok(toast) => toast,
        Err(_) => return false,
    };
    let notifier = ToastNotificationManager::CreateToastNotifier()
        .or_else(|_| ToastNotificationManager::CreateToastNotifierWithId(&HSTRING::from(APP_ID)));
    match notifier {
        Ok(notifier) => notifier.Show(&toast).is_ok(),
        Err(_) => false,
    }
}

#[cfg(windows)]
fn escape_xml(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

pub fn open_target(target: &str, is_url: bool) -> bool {
    if is_url && !(target.starts_with("https://") || target.starts_with("http://")) {
        return false;
    }
    #[cfg(windows)]
    {
        let operation = wide_null("open");
        let file = wide_null(target);
        unsafe {
            let result = ShellExecuteW(
                HWND::default(),
                PCWSTR(operation.as_ptr()),
                PCWSTR(file.as_ptr()),
                PCWSTR::null(),
                PCWSTR::null(),
                SW_SHOWNORMAL,
            );
            result.0 as isize > 32
        }
    }
    #[cfg(not(windows))]
    {
        let _ = target;
        false
    }
}

#[cfg(windows)]
fn query_gsmtc() -> MusicState {
    ensure_winrt();
    let manager = match GlobalSystemMediaTransportControlsSessionManager::RequestAsync()
        .and_then(|operation| operation.get())
    {
        Ok(manager) => manager,
        Err(_) => return MusicState::default(),
    };
    let session = match manager.GetCurrentSession() {
        Ok(session) => session,
        Err(_) => return MusicState::default(),
    };
    let playing = session
        .GetPlaybackInfo()
        .and_then(|info| info.PlaybackStatus())
        .map(|status| status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing)
        .unwrap_or(false);
    if !playing {
        return MusicState::default();
    }
    let properties = match session
        .TryGetMediaPropertiesAsync()
        .and_then(|operation| operation.get())
    {
        Ok(properties) => properties,
        Err(_) => return MusicState::default(),
    };
    let track = properties
        .Title()
        .map(|text| text.to_string())
        .unwrap_or_default();
    let artist = properties
        .Artist()
        .map(|text| text.to_string())
        .unwrap_or_default();
    let player = session
        .SourceAppUserModelId()
        .map(|text| text.to_string())
        .unwrap_or_default();
    MusicState {
        playing: true,
        track,
        artist,
        player,
    }
}

pub fn current_music_state() -> MusicState {
    #[cfg(windows)]
    {
        // Run the blocking WinRT async operation on a worker thread. A sync
        // Tauri command may be executing on the main STA thread, where waiting
        // on an IAsyncOperation without a message pump can deadlock.
        std::thread::spawn(query_gsmtc).join().unwrap_or_default()
    }
    #[cfg(not(windows))]
    {
        MusicState::default()
    }
}

pub fn start_music_monitor(app: AppHandle) {
    #[cfg(windows)]
    thread::spawn(move || {
        let mut last = MusicState::default();
        loop {
            let state = query_gsmtc();
            if state.playing != last.playing || (state.playing && state.track != last.track) {
                let _ = app.emit("gupet://music", &state);
                last = state;
            }
            thread::sleep(Duration::from_secs(2));
        }
    });
    #[cfg(not(windows))]
    {
        let _ = app;
    }
}

#[cfg(windows)]
fn notification_snapshot(
    listener: &UserNotificationListener,
) -> Option<Vec<windows::UI::Notifications::UserNotification>> {
    listener
        .GetNotificationsAsync(NotificationKinds::Toast)
        .ok()?
        .get()
        .ok()
        .map(|view| view.into_iter().collect())
}

#[cfg(windows)]
fn notification_app_id(notification: &windows::UI::Notifications::UserNotification) -> String {
    notification
        .AppInfo()
        .and_then(|app| app.AppUserModelId())
        .map(|id| id.to_string())
        .unwrap_or_default()
}

pub fn start_notification_monitor(app: AppHandle) {
    #[cfg(windows)]
    thread::spawn(move || {
        ensure_winrt();
        let mut seen = HashSet::<u32>::new();
        let mut initialized = false;
        let mut asked_for_access = false;
        loop {
            let listener = match UserNotificationListener::Current() {
                Ok(listener) => listener,
                Err(_) => {
                    thread::sleep(Duration::from_secs(30));
                    continue;
                }
            };
            let access = listener
                .GetAccessStatus()
                .unwrap_or(UserNotificationListenerAccessStatus::Unspecified);
            if access != UserNotificationListenerAccessStatus::Allowed {
                if access == UserNotificationListenerAccessStatus::Unspecified && !asked_for_access
                {
                    asked_for_access = true;
                    if let Ok(operation) = listener.RequestAccessAsync() {
                        let _ = operation.get();
                    }
                }
                thread::sleep(Duration::from_secs(15));
                continue;
            }
            let notifications = match notification_snapshot(&listener) {
                Some(notifications) => notifications,
                None => {
                    thread::sleep(Duration::from_secs(15));
                    continue;
                }
            };
            for notification in notifications {
                let Ok(id) = notification.Id() else {
                    continue;
                };
                if !initialized {
                    seen.insert(id);
                    continue;
                }
                if !seen.insert(id) {
                    continue;
                }
                if notification_app_id(&notification).eq_ignore_ascii_case(APP_ID) {
                    continue;
                }
                if now_ms() < OWN_NOTIFICATIONS_UNTIL_MS.load(Ordering::Relaxed) {
                    continue;
                }
                let _ = app.emit("gupet://notification", json!({ "source": "desktop" }));
            }
            initialized = true;
            thread::sleep(Duration::from_secs(2));
        }
    });
    #[cfg(not(windows))]
    {
        let _ = app;
    }
}
