use serde::Serialize;
use serde_json::json;
use std::io::{BufRead, BufReader};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter};

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

pub fn send_notification(title: &str, body: &str) -> bool {
    OWN_NOTIFICATIONS_UNTIL_MS.store(now_ms().saturating_add(1800), Ordering::Relaxed);
    if let Ok(status) = Command::new("notify-send")
        .args(["-a", "GUPet", "-i", "gupet", "-t", "6000", title, body])
        .status()
    {
        if status.success() {
            return true;
        }
    }
    let fallback = Command::new("gdbus")
        .args([
            "call",
            "--session",
            "--dest",
            "org.freedesktop.Notifications",
            "--object-path",
            "/org/freedesktop/Notifications",
            "--method",
            "org.freedesktop.Notifications.Notify",
            "GUPet",
            "0",
            "",
            title,
            body,
            "[]",
            "{}",
            "6000",
        ])
        .output();
    fallback.map(|out| out.status.success()).unwrap_or(false)
}

pub fn open_target(target: &str, is_url: bool) -> bool {
    if is_url && !(target.starts_with("https://") || target.starts_with("http://")) {
        return false;
    }
    for program in ["xdg-open", "gio"] {
        let mut command = Command::new(program);
        if program == "gio" {
            command.arg("open");
        }
        let status = command
            .arg(target)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
        if let Ok(status) = status {
            if status.success() {
                return true;
            }
        }
    }
    false
}

fn query_mpris() -> MusicState {
    let finder = match mpris::PlayerFinder::new() {
        Ok(finder) => finder,
        Err(_) => return MusicState::default(),
    };
    let players = match finder.find_all() {
        Ok(players) => players,
        Err(_) => return MusicState::default(),
    };
    for player in players {
        let playing = player
            .get_playback_status()
            .map(|status| status == mpris::PlaybackStatus::Playing)
            .unwrap_or(false);
        if !playing {
            continue;
        }
        let metadata = player.get_metadata();
        let (track, artist) = match metadata {
            Ok(metadata) => {
                let track = metadata.title().unwrap_or("").to_string();
                let artist = metadata
                    .artists()
                    .map(|values| values.join(", "))
                    .unwrap_or_default();
                (track, artist)
            }
            Err(_) => (String::new(), String::new()),
        };
        return MusicState {
            playing: true,
            track,
            artist,
            player: player.identity().to_string(),
        };
    }
    MusicState::default()
}

pub fn current_music_state() -> MusicState {
    query_mpris()
}

pub fn start_music_monitor(app: AppHandle) {
    thread::spawn(move || {
        let mut last = MusicState::default();
        loop {
            let state = query_mpris();
            if state.playing != last.playing || (state.playing && state.track != last.track) {
                let _ = app.emit("gupet://music", &state);
                last = state;
            }
            thread::sleep(Duration::from_secs(2));
        }
    });
}

pub fn start_notification_monitor(app: AppHandle) {
    thread::spawn(move || loop {
        let child = Command::new("dbus-monitor")
            .arg("type='method_call',interface='org.freedesktop.Notifications',member='Notify'")
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn();
        let Ok(mut child) = child else {
            thread::sleep(Duration::from_secs(30));
            continue;
        };
        let Some(stdout) = child.stdout.take() else {
            let _ = child.kill();
            thread::sleep(Duration::from_secs(5));
            continue;
        };
        let reader = BufReader::new(stdout);
        let mut last_emit: Option<Instant> = None;
        for line in reader.lines().map_while(Result::ok) {
            if line.contains("member=Notify") {
                let now = Instant::now();
                let own_notification =
                    now_ms() < OWN_NOTIFICATIONS_UNTIL_MS.load(Ordering::Relaxed);
                if !own_notification
                    && last_emit
                        .map(|last| now.duration_since(last) > Duration::from_millis(700))
                        .unwrap_or(true)
                {
                    last_emit = Some(now);
                    let _ = app.emit("gupet://notification", json!({ "source": "desktop" }));
                }
            }
        }
        let _ = child.kill();
        let _ = child.wait();
        thread::sleep(Duration::from_secs(5));
    });
}
