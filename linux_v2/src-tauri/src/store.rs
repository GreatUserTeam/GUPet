use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

pub const BUILTIN_SKIN: &str = "builtin";

pub fn config_dir() -> PathBuf {
    dirs::config_dir()
        .or_else(|| dirs::home_dir().map(|h| h.join(".config")))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("gupet")
}

pub fn data_dir() -> PathBuf {
    dirs::data_dir()
        .or_else(|| dirs::home_dir().map(|h| h.join(".local/share")))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("gupet")
}

pub fn skins_config_dir() -> PathBuf {
    config_dir().join("skins")
}

pub fn skins_data_dir() -> PathBuf {
    data_dir().join("skins")
}

pub fn settings_path() -> PathBuf {
    config_dir().join("settings.json")
}

pub fn autostart_path() -> PathBuf {
    dirs::config_dir()
        .or_else(|| dirs::home_dir().map(|h| h.join(".config")))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("autostart")
        .join("gupet.desktop")
}

pub fn default_pet() -> Value {
    json!({
        "show": true,
        "species": "gupet",
        "palette": 0,
        "colorHex": null,
        "sizeDp": 4.0,
        "wander": true,
        "phrases": true,
        "gravity": false,
        "gravityStrength": 1.0,
        "shake": true,
        "haptics": true,
        "edgeVibration": true,
        "poop": true,
        "maxPoops": 3,
        "poopSize": 1.0,
        "poopInterval": 1.0,
        "poopColor": "#8b5a2b",
        "poopFadeMs": 500,
        "weight": 1.0,
        "reactToPoop": true,
        "reactToOtherPets": true,
        "wakeOnPetTouch": true,
        "hideOnAppOpen": false,
        "canHang": true,
        "sleepMinutes": 3,
        "reactNotifications": false,
        "dance": true,
        "persistPosition": true,
        "phraseColor": "#ffffff",
        "phraseFontSize": 14.0,
        "phraseBgColor": "#0b0b0b",
        "phraseBgAlpha": 0.85,
        "phraseOutlineColor": "#ff4d30",
        "phraseOutlineWidth": 2.0,
        "phraseDurationMs": 3000,
        "phraseFadeMs": 400,
        "phraseFadeStyle": "fade",
        "slideFriction": 1.0,
        "customSkinId": null,
        "customSkinRevision": 0,
        "activeSkinIds": [BUILTIN_SKIN],
        "petsCollide": true,
        "lastPosX": null,
        "lastPosY": null
    })
}

pub fn default_ui() -> BTreeMap<String, String> {
    let mut map = BTreeMap::new();
    map.insert("ui_theme".to_string(), "dark".to_string());
    map.insert("ui_accent".to_string(), "#ff4d30".to_string());
    map.insert("web_lang".to_string(), "auto".to_string());
    map.insert("lang".to_string(), "auto".to_string());
    map.insert("scroll_mode".to_string(), "remember".to_string());
    map.insert("ask_delete_skin".to_string(), "on".to_string());
    map.insert("ask_delete_phrase".to_string(), "on".to_string());
    map
}

pub fn default_phrases() -> Value {
    serde_json::from_str(include_str!("default_phrases.json"))
        .unwrap_or_else(|_| Value::Array(Vec::new()))
}

fn merge_pet_with_defaults(value: Value) -> Value {
    let mut base = default_pet();
    let (Some(base_obj), Some(input)) = (base.as_object_mut(), value.as_object()) else {
        return base;
    };
    for (key, val) in input {
        base_obj.insert(key.clone(), val.clone());
    }
    base
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Persisted {
    pub ui: BTreeMap<String, String>,
    pub pet: Value,
    pub phrases: Value,
    pub running: bool,
    pub active_skin_ids: Vec<String>,
    pub poops_cleaned: u64,
}

impl Default for Persisted {
    fn default() -> Self {
        Self {
            ui: default_ui(),
            pet: default_pet(),
            phrases: default_phrases(),
            running: true,
            active_skin_ids: vec![BUILTIN_SKIN.to_string()],
            poops_cleaned: 0,
        }
    }
}

impl Persisted {
    fn normalize(&mut self) {
        for (key, value) in default_ui() {
            self.ui.entry(key).or_insert(value);
        }
        self.pet = merge_pet_with_defaults(std::mem::take(&mut self.pet));
        if !self.phrases.is_array() {
            self.phrases = default_phrases();
        }
        self.active_skin_ids = clean_ids(std::mem::take(&mut self.active_skin_ids));
        if self.active_skin_ids.is_empty() {
            self.active_skin_ids.push(BUILTIN_SKIN.to_string());
        }
        if let Some(obj) = self.pet.as_object_mut() {
            obj.insert(
                "activeSkinIds".to_string(),
                json!(self.active_skin_ids.clone()),
            );
        }
    }
}

fn clean_ids(ids: Vec<String>) -> Vec<String> {
    let mut out = Vec::new();
    for id in ids {
        let id = id.trim().to_string();
        if id.is_empty() || out.contains(&id) {
            continue;
        }
        out.push(id);
    }
    out
}

pub struct SettingsStore {
    pub data: Persisted,
    pub path: PathBuf,
}

impl SettingsStore {
    pub fn load() -> Self {
        let path = settings_path();
        let mut data = fs::read_to_string(&path)
            .ok()
            .and_then(|text| serde_json::from_str::<Persisted>(&text).ok())
            .unwrap_or_default();
        data.normalize();
        let store = Self { data, path };
        if let Err(err) = store.save() {
            eprintln!("[gupet] could not save initial settings: {err}");
        }
        store
    }

    pub fn save(&self) -> Result<(), String> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)
                .map_err(|e| format!("Не удалось создать папку настроек: {e}"))?;
        }
        let text = serde_json::to_string_pretty(&self.data)
            .map_err(|e| format!("Не удалось сериализовать настройки: {e}"))?;
        let tmp = self.path.with_extension("json.tmp");
        {
            let mut file = fs::File::create(&tmp)
                .map_err(|e| format!("Не удалось записать настройки: {e}"))?;
            file.write_all(text.as_bytes())
                .map_err(|e| format!("Не удалось записать настройки: {e}"))?;
            file.flush().ok();
        }
        fs::rename(&tmp, &self.path).map_err(|e| format!("Не удалось сохранить настройки: {e}"))?;
        Ok(())
    }

    pub fn prefs(&self) -> BTreeMap<String, String> {
        self.data.ui.clone()
    }

    pub fn set_pref(&mut self, key: &str, value: String) -> Result<(), String> {
        if value.is_empty() {
            self.data.ui.remove(key);
        } else {
            self.data.ui.insert(key.to_string(), value);
        }
        self.save()
    }

    pub fn pet_for_ui(&self) -> Value {
        let mut value = self.data.pet.clone();
        if let Some(obj) = value.as_object_mut() {
            obj.insert("show".to_string(), json!(self.data.running));
            obj.insert(
                "activeSkinIds".to_string(),
                json!(self.data.active_skin_ids.clone()),
            );
        }
        value
    }

    pub fn set_pet(&mut self, value: Value) -> Result<(), String> {
        let Some(input) = value.as_object() else {
            return Err("Некорректные настройки питомца".to_string());
        };
        let current = self
            .data
            .pet
            .as_object_mut()
            .ok_or_else(|| "Некорректные настройки питомца".to_string())?;
        for (key, val) in input {
            if key == "activeSkinIds" || key == "show" {
                continue;
            }
            current.insert(key.clone(), val.clone());
        }
        if !self.data.phrases.is_array() {
            self.data.phrases = default_phrases();
        }
        self.save()
    }

    pub fn set_phrases(&mut self, value: Value) -> Result<(), String> {
        if !value.is_array() {
            return Err("Некорректный список реплик".to_string());
        }
        let cleaned: Vec<Value> = value
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|item| {
                let obj = item.as_object()?;
                let text = obj.get("text")?.as_str()?.trim();
                if text.is_empty() {
                    return None;
                }
                Some(json!({
                    "id": obj.get("id").and_then(Value::as_str).unwrap_or("phrase"),
                    "text": text,
                    "context": obj.get("context").and_then(Value::as_str).unwrap_or("idle"),
                    "weight": obj.get("weight").and_then(Value::as_i64).unwrap_or(1).clamp(1, 10),
                    "enabled": obj.get("enabled").and_then(Value::as_bool).unwrap_or(true)
                }))
            })
            .collect();
        self.data.phrases = Value::Array(cleaned);
        self.save()
    }

    pub fn set_running(&mut self, running: bool) -> Result<(), String> {
        self.data.running = running;
        if let Some(obj) = self.data.pet.as_object_mut() {
            obj.insert("show".to_string(), json!(running));
        }
        self.save()
    }

    pub fn set_active_skins(
        &mut self,
        ids: Vec<String>,
        custom_skin: Option<String>,
        species: &str,
    ) -> Result<(), String> {
        let mut ids = clean_ids(ids);
        if ids.is_empty() {
            ids.push(BUILTIN_SKIN.to_string());
        }
        self.data.active_skin_ids = ids.clone();
        if let Some(obj) = self.data.pet.as_object_mut() {
            obj.insert("activeSkinIds".to_string(), json!(ids));
            obj.insert(
                "customSkinId".to_string(),
                custom_skin.map(Value::String).unwrap_or(Value::Null),
            );
            obj.insert("species".to_string(), json!(species));
        }
        self.save()
    }

    pub fn active_skins(&self) -> Vec<String> {
        self.data.active_skin_ids.clone()
    }

    pub fn running(&self) -> bool {
        self.data.running
    }

    pub fn phrases(&self) -> Value {
        self.data.phrases.clone()
    }

    pub fn pet_value(&self) -> Value {
        self.data.pet.clone()
    }

    pub fn settings_path_string(&self) -> String {
        self.path.display().to_string()
    }

    pub fn add_cleaned_poops(&mut self, count: u64) {
        self.data.poops_cleaned = self.data.poops_cleaned.saturating_add(count);
        let _ = self.save();
    }
}

pub fn is_autostart_enabled() -> bool {
    autostart_path().is_file()
}

fn desktop_exec_quote(path: &Path) -> String {
    let raw = path.display().to_string();
    let escaped = raw
        .replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('`', "\\`")
        .replace('$', "\\$");
    format!("\"{escaped}\"")
}

pub fn set_autostart(enabled: bool) -> Result<bool, String> {
    let path = autostart_path();
    if enabled {
        let exe = std::env::var_os("APPIMAGE")
            .map(PathBuf::from)
            .filter(|p| p.exists())
            .or_else(|| std::env::current_exe().ok())
            .ok_or_else(|| "Не удалось определить путь к GUPet".to_string())?;
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)
                .map_err(|e| format!("Не удалось создать папку автозапуска: {e}"))?;
        }
        let content = format!(
            "[Desktop Entry]\n\
Type=Application\n\
Version=1.0\n\
Name=GUPet\n\
Comment=Pixel pet on your desktop\n\
Exec={} --autostart\n\
Icon=gupet\n\
Terminal=false\n\
Categories=Utility;\n\
StartupNotify=false\n\
X-GNOME-Autostart-enabled=true\n",
            desktop_exec_quote(&exe)
        );
        fs::write(&path, content).map_err(|e| format!("Не удалось включить автозапуск: {e}"))?;
    } else if path.exists() {
        fs::remove_file(&path).map_err(|e| format!("Не удалось отключить автозапуск: {e}"))?;
    }
    Ok(is_autostart_enabled())
}
