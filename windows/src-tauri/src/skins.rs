use crate::store::{data_dir, skins_config_dir, skins_data_dir, BUILTIN_SKIN};
use base64::Engine;
use image::{DynamicImage, ImageFormat, RgbaImage};
use serde::Serialize;
use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::{Cursor, Read, Write};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

pub const WIDTH: u32 = 18;
pub const HEIGHT: u32 = 13;
pub const MAX_ENTRIES: usize = 20;
pub const MAX_TOTAL_BYTES: usize = 2 * 1024 * 1024;
pub const MAX_IMAGE_BYTES: usize = 512 * 1024;
pub const MAX_MANIFEST_BYTES: usize = 64 * 1024;
// Дополнительные кадры для ПК встраиваются в manifest.json, чтобы Android
// (лимит manifest.json — 64 КБ) мог открывать такие скины и просто игнорировать
// незнакомую секцию pc_settings. Поэтому base64 одного кадра ограничен.
const MAX_EMBEDDED_FRAME_BASE64: usize = 8 * 1024;

pub const FRAME_NAMES: [&str; 10] = [
    "idle",
    "blink",
    "happy",
    "sleep",
    "dance",
    "surprised",
    "dizzy",
    "angry",
    "love",
    "poop",
];

pub const PC_FRAME_NAMES: [&str; 4] = ["cursor_hover", "cursor_near", "cursor_far", "app_open"];

pub fn default_pc_settings() -> Value {
    json!({
        "extendedFrames": false,
        "cursorTouch": false,
        "cursorNear": false,
        "cursorFar": false,
        "appOpen": false,
        "hideOnAppOpen": false,
        "hideMode": "all",
        "hideFilter": "only",
        "hideApps": [],
        "cursorReaction": "none",
        "cursorDetection": "everywhere",
        "cursorDistance": 5,
        "cursorSpeed": 50,
        "extraFrames": {}
    })
}

fn obj_bool(obj: Option<&Map<String, Value>>, key: &str, default: bool) -> bool {
    obj.and_then(|o| o.get(key))
        .and_then(Value::as_bool)
        .unwrap_or(default)
}

pub fn normalize_pc_settings(value: &Value) -> Value {
    let obj = value.as_object();
    let reaction = match obj
        .and_then(|o| o.get("cursorReaction"))
        .and_then(Value::as_str)
        .unwrap_or("none")
    {
        "chase" => "chase",
        "flee" => "flee",
        _ => "none",
    };
    let detection = match obj
        .and_then(|o| o.get("cursorDetection"))
        .and_then(Value::as_str)
        .unwrap_or("everywhere")
    {
        "distance" => "distance",
        _ => "everywhere",
    };
    let distance = obj
        .and_then(|o| o.get("cursorDistance"))
        .and_then(Value::as_i64)
        .unwrap_or(5)
        .clamp(1, 10);
    let speed = obj
        .and_then(|o| o.get("cursorSpeed"))
        .and_then(Value::as_i64)
        .unwrap_or(50)
        .clamp(1, 100);
    let mut hide_apps = Vec::new();
    if let Some(list) = obj
        .and_then(|o| o.get("hideApps"))
        .and_then(Value::as_array)
    {
        for item in list {
            let Some(text) = item.as_str() else {
                continue;
            };
            let text = text.trim().to_lowercase();
            if text.is_empty() || text.chars().count() > 120 || hide_apps.contains(&text) {
                continue;
            }
            hide_apps.push(text);
            if hide_apps.len() >= 64 {
                break;
            }
        }
    }
    let hide_mode = match obj
        .and_then(|o| o.get("hideMode"))
        .and_then(Value::as_str)
        .unwrap_or("")
    {
        "all" => "all",
        "selected" => "selected",
        _ if hide_apps.is_empty() => "all",
        _ => "selected",
    };
    let hide_filter = match obj
        .and_then(|o| o.get("hideFilter"))
        .and_then(Value::as_str)
        .unwrap_or("only")
    {
        "except" => "except",
        _ => "only",
    };
    let mut extra_frames = Map::new();
    if let Some(extra) = obj
        .and_then(|o| o.get("extraFrames"))
        .and_then(Value::as_object)
    {
        for frame in PC_FRAME_NAMES {
            let Some(data) = extra.get(frame).and_then(Value::as_str) else {
                continue;
            };
            if !data.is_empty() && data.len() <= MAX_EMBEDDED_FRAME_BASE64 {
                extra_frames.insert(frame.to_string(), Value::String(data.to_string()));
            }
        }
    }
    json!({
        "extendedFrames": obj_bool(obj, "extendedFrames", false),
        "cursorTouch": obj_bool(obj, "cursorTouch", false),
        "cursorNear": obj_bool(obj, "cursorNear", false),
        "cursorFar": obj_bool(obj, "cursorFar", false),
        "appOpen": obj_bool(obj, "appOpen", false),
        "hideOnAppOpen": obj_bool(obj, "hideOnAppOpen", false),
        "hideMode": hide_mode,
        "hideFilter": hide_filter,
        "hideApps": hide_apps,
        "cursorReaction": reaction,
        "cursorDetection": detection,
        "cursorDistance": distance,
        "cursorSpeed": speed,
        "extraFrames": Value::Object(extra_frames)
    })
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SkinInfo {
    pub id: String,
    pub name: String,
    pub author: String,
    pub builtin: bool,
    pub has_settings: bool,
    pub has_frames: bool,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FrameInfo {
    pub exists: bool,
    pub file_name: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FullSkin {
    pub id: String,
    pub name: String,
    pub author: String,
    pub builtin: bool,
    pub has_settings: bool,
    pub has_frames: bool,
    pub preview: String,
    pub revision: u64,
    pub settings: Option<Value>,
    pub pc_settings: Value,
    pub frames: BTreeMap<String, FrameInfo>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FullSkins {
    pub active: String,
    pub active_ids: Vec<String>,
    pub skins: Vec<FullSkin>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeSkin {
    pub id: String,
    pub name: String,
    pub revision: u64,
    pub settings: Option<Value>,
    pub pc_settings: Value,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SkinFrames {
    pub id: String,
    pub revision: u64,
    pub frames: BTreeMap<String, String>,
    pub poop: Option<String>,
}

#[derive(Clone)]
pub struct Manifest {
    pub id: String,
    pub name: String,
    pub author: String,
    pub frames: BTreeMap<String, String>,
    pub settings: Option<Value>,
    pub pc_settings: Value,
    pub raw: Value,
    pub revision: u64,
}

pub fn valid_id(id: &str) -> bool {
    let bytes = id.as_bytes();
    if bytes.is_empty() || bytes.len() > 40 {
        return false;
    }
    let first = bytes[0] as char;
    if !(first.is_ascii_lowercase() || first.is_ascii_digit()) {
        return false;
    }
    bytes
        .iter()
        .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || *b == b'_' || *b == b'-')
}

fn png_data_url(bytes: &[u8]) -> Option<String> {
    if !valid_png(bytes) {
        return None;
    }
    Some(format!(
        "data:image/png;base64,{}",
        base64::engine::general_purpose::STANDARD.encode(bytes)
    ))
}

pub fn valid_png(bytes: &[u8]) -> bool {
    if bytes.is_empty() || bytes.len() > MAX_IMAGE_BYTES {
        return false;
    }
    let reader = match image::ImageReader::new(Cursor::new(bytes)).with_guessed_format() {
        Ok(reader) => reader,
        Err(_) => return false,
    };
    if reader.format() != Some(ImageFormat::Png) {
        return false;
    }
    match reader.into_dimensions() {
        Ok((w, h)) if w == WIDTH && h == HEIGHT => {}
        _ => return false,
    }
    image::load_from_memory_with_format(bytes, ImageFormat::Png).is_ok()
}

fn base64_decode(data: &str) -> Result<Vec<u8>, String> {
    base64::engine::general_purpose::STANDARD
        .decode(data)
        .map_err(|_| "Некорректные данные файла".to_string())
}

fn modified_ms(path: &Path) -> u64 {
    fs::metadata(path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn folder_revision(folder: &Path) -> u64 {
    let mut revision = modified_ms(&folder.join("manifest.json"));
    if let Ok(entries) = fs::read_dir(folder) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|s| s.to_str()) == Some("png") {
                revision = revision.max(modified_ms(&path));
            }
        }
    }
    revision
}

fn frame_file_name(key: &str) -> String {
    format!("{key}.png")
}

fn extra_frame_base64(manifest: &Manifest, frame: &str) -> Option<String> {
    manifest
        .pc_settings
        .get("extraFrames")
        .and_then(Value::as_object)
        .and_then(|frames| frames.get(frame))
        .and_then(Value::as_str)
        .filter(|data| !data.is_empty())
        .map(str::to_string)
}

fn extra_frame_info(manifest: &Manifest, frame: &str) -> FrameInfo {
    let exists = extra_frame_base64(manifest, frame)
        .and_then(|data| base64_decode(&data).ok())
        .map(|bytes| valid_png(&bytes))
        .unwrap_or(false);
    FrameInfo {
        exists,
        file_name: if exists {
            format!("pc:{frame}")
        } else {
            String::new()
        },
    }
}

fn read_json(path: &Path) -> Option<Value> {
    let meta = fs::metadata(path).ok()?;
    if !meta.is_file() || meta.len() > MAX_MANIFEST_BYTES as u64 {
        return None;
    }
    let text = fs::read_to_string(path).ok()?;
    serde_json::from_str(&text).ok()
}

fn parse_manifest_value(
    value: &Value,
    strict: bool,
    folder_id: Option<&str>,
) -> Result<Manifest, String> {
    let obj = value
        .as_object()
        .ok_or_else(|| "manifest.json должен содержать объект".to_string())?;
    if strict {
        if obj.get("format").and_then(Value::as_str) != Some("GUPet") {
            return Err("Неподдерживаемый формат GUPet".to_string());
        }
        if obj.get("formatVersion").and_then(Value::as_i64) != Some(1) {
            return Err("Неподдерживаемая версия формата GUPet".to_string());
        }
        if obj.get("width").and_then(Value::as_u64) != Some(WIDTH as u64)
            || obj.get("height").and_then(Value::as_u64) != Some(HEIGHT as u64)
        {
            return Err("Размер каждого PNG должен быть 18 × 13 пикселей".to_string());
        }
    }
    let id = obj
        .get("id")
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim()
        .to_string();
    if !valid_id(&id) {
        return Err("В id разрешены строчные латинские буквы, цифры, _ и -".to_string());
    }
    if let Some(expected) = folder_id {
        if id != expected {
            return Err("id в manifest.json не совпадает с именем папки".to_string());
        }
    }
    let name = obj
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim()
        .to_string();
    if name.is_empty() || name.chars().count() > 40 || name.chars().any(char::is_control) {
        return Err("Название должно содержать от 1 до 40 печатных символов".to_string());
    }
    let author = obj
        .get("author")
        .and_then(Value::as_str)
        .unwrap_or("Не указан")
        .trim()
        .chars()
        .filter(|c| !c.is_control())
        .take(40)
        .collect::<String>();
    let author = if author.trim().is_empty() {
        "Не указан".to_string()
    } else {
        author
    };
    let frames_obj = obj
        .get("frames")
        .and_then(Value::as_object)
        .ok_or_else(|| "В manifest.json нет списка frames".to_string())?;
    let mut frames = BTreeMap::new();
    for (key, value) in frames_obj {
        if !FRAME_NAMES.contains(&key.as_str()) {
            return Err(format!("Неизвестный кадр: {key}"));
        }
        let file_name = value.as_str().unwrap_or("").trim();
        if file_name != frame_file_name(key) {
            return Err(format!("Кадр {key} должен называться {key}.png"));
        }
        frames.insert(key.clone(), file_name.to_string());
    }
    if !frames.is_empty() && !frames.contains_key("idle") {
        return Err("Нужен обязательный кадр idle.png".to_string());
    }
    let settings = obj
        .get("settings")
        .and_then(|v| if v.is_object() { Some(v.clone()) } else { None });
    let pc_settings = normalize_pc_settings(obj.get("pc_settings").unwrap_or(&Value::Null));
    Ok(Manifest {
        id,
        name,
        author,
        frames,
        settings,
        pc_settings,
        raw: value.clone(),
        revision: 0,
    })
}

fn synthesized_manifest(folder: &Path) -> Option<Manifest> {
    let id = folder.file_name()?.to_str()?.to_string();
    if !valid_id(&id) {
        return None;
    }
    let mut frames = BTreeMap::new();
    for key in FRAME_NAMES {
        let path = folder.join(frame_file_name(key));
        if path.is_file()
            && fs::metadata(&path)
                .map(|m| m.len() <= MAX_IMAGE_BYTES as u64)
                .unwrap_or(false)
        {
            if let Ok(bytes) = fs::read(&path) {
                if valid_png(&bytes) {
                    frames.insert(key.to_string(), frame_file_name(key));
                }
            }
        }
    }
    if !frames.contains_key("idle") {
        return None;
    }
    let name = id.clone();
    let raw = json!({
        "format": "GUPet",
        "formatVersion": 1,
        "version": 2,
        "width": WIDTH,
        "height": HEIGHT,
        "id": id,
        "name": name,
        "author": "Не указан",
        "frames": frames.clone(),
        "settings": Value::Null,
        "pc_settings": default_pc_settings()
    });
    Some(Manifest {
        id,
        name,
        author: "Не указан".to_string(),
        frames,
        settings: None,
        pc_settings: default_pc_settings(),
        raw,
        revision: 0,
    })
}

pub fn manifest_for_folder(folder: &Path) -> Option<Manifest> {
    if !folder.is_dir() {
        return None;
    }
    let folder_id = folder.file_name()?.to_str()?;
    if !valid_id(folder_id) {
        return None;
    }
    let path = folder.join("manifest.json");
    let mut manifest = if path.is_file() {
        let value = read_json(&path)?;
        parse_manifest_value(&value, true, Some(folder_id)).ok()?
    } else {
        synthesized_manifest(folder)?
    };
    manifest.revision = folder_revision(folder);
    Some(manifest)
}

pub fn roots() -> Vec<PathBuf> {
    vec![skins_config_dir(), skins_data_dir()]
}

pub fn find_skin_folder(id: &str) -> Option<PathBuf> {
    if !valid_id(id) {
        return None;
    }
    for root in roots() {
        let folder = root.join(id);
        if folder.is_dir() && folder.join("manifest.json").is_file() {
            return Some(folder);
        }
        if folder.is_dir() && folder.join("idle.png").is_file() {
            return Some(folder);
        }
    }
    None
}

fn scan_folders() -> Vec<(PathBuf, Manifest)> {
    let mut found: BTreeMap<String, (PathBuf, Manifest)> = BTreeMap::new();
    for root in roots() {
        let Ok(entries) = fs::read_dir(&root) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_dir() {
                continue;
            }
            let Some(name) = path.file_name().and_then(|s| s.to_str()) else {
                continue;
            };
            if name.starts_with('.') || found.contains_key(name) {
                continue;
            }
            if let Some(manifest) = manifest_for_folder(&path) {
                found.insert(name.to_string(), (path, manifest));
            }
        }
    }
    found.into_values().collect()
}

fn builtin_info() -> SkinInfo {
    SkinInfo {
        id: BUILTIN_SKIN.to_string(),
        name: "Стандартный GUPet".to_string(),
        author: "GUTeam".to_string(),
        builtin: true,
        has_settings: true,
        has_frames: false,
    }
}

pub fn builtin_full() -> FullSkin {
    let mut frames = BTreeMap::new();
    for frame in FRAME_NAMES {
        frames.insert(
            frame.to_string(),
            FrameInfo {
                exists: false,
                file_name: String::new(),
            },
        );
    }
    for frame in PC_FRAME_NAMES {
        frames.insert(
            frame.to_string(),
            FrameInfo {
                exists: false,
                file_name: String::new(),
            },
        );
    }
    FullSkin {
        id: BUILTIN_SKIN.to_string(),
        name: "Стандартный GUPet".to_string(),
        author: "GUTeam".to_string(),
        builtin: true,
        has_settings: true,
        has_frames: false,
        preview: String::new(),
        revision: 0,
        settings: None,
        pc_settings: default_pc_settings(),
        frames,
    }
}

pub fn list() -> Vec<SkinInfo> {
    let mut out = vec![builtin_info()];
    for (_, manifest) in scan_folders() {
        out.push(SkinInfo {
            id: manifest.id.clone(),
            name: manifest.name.clone(),
            author: manifest.author.clone(),
            builtin: false,
            has_settings: manifest.settings.is_some(),
            has_frames: manifest.frames.contains_key("idle"),
        });
    }
    out
}

fn frame_info_for(folder: &Path, frame: &str) -> FrameInfo {
    let file = folder.join(frame_file_name(frame));
    let exists = file.is_file()
        && fs::metadata(&file)
            .map(|m| m.len() <= MAX_IMAGE_BYTES as u64)
            .unwrap_or(false)
        && fs::read(&file).map(|b| valid_png(&b)).unwrap_or(false);
    FrameInfo {
        exists,
        file_name: if exists {
            frame_file_name(frame)
        } else {
            String::new()
        },
    }
}

fn full_skin_from(folder: &Path, manifest: &Manifest) -> FullSkin {
    let mut frames = BTreeMap::new();
    for frame in FRAME_NAMES {
        frames.insert(frame.to_string(), frame_info_for(folder, frame));
    }
    for frame in PC_FRAME_NAMES {
        frames.insert(frame.to_string(), extra_frame_info(manifest, frame));
    }
    let preview = fs::read(folder.join("idle.png"))
        .ok()
        .and_then(|bytes| png_data_url(&bytes))
        .unwrap_or_default();
    FullSkin {
        id: manifest.id.clone(),
        name: manifest.name.clone(),
        author: manifest.author.clone(),
        builtin: false,
        has_settings: manifest.settings.is_some(),
        has_frames: manifest.frames.contains_key("idle"),
        preview,
        revision: manifest.revision,
        settings: manifest.settings.clone(),
        pc_settings: manifest.pc_settings.clone(),
        frames,
    }
}

pub fn full_skins(active_ids: &[String]) -> FullSkins {
    let active = active_ids
        .first()
        .cloned()
        .unwrap_or_else(|| BUILTIN_SKIN.to_string());
    let mut skins = vec![builtin_full()];
    for (folder, manifest) in scan_folders() {
        skins.push(full_skin_from(&folder, &manifest));
    }
    FullSkins {
        active,
        active_ids: active_ids.to_vec(),
        skins,
    }
}

pub fn full_skin(id: &str) -> Option<FullSkin> {
    if id == BUILTIN_SKIN {
        return Some(builtin_full());
    }
    let folder = find_skin_folder(id)?;
    let manifest = manifest_for_folder(&folder)?;
    Some(full_skin_from(&folder, &manifest))
}

pub fn runtime_skins(ids: &[String]) -> Vec<RuntimeSkin> {
    let mut out = Vec::new();
    for id in ids {
        if id == BUILTIN_SKIN {
            out.push(RuntimeSkin {
                id: id.clone(),
                name: "Стандартный GUPet".to_string(),
                revision: 0,
                settings: None,
                pc_settings: default_pc_settings(),
            });
            continue;
        }
        if let Some(folder) = find_skin_folder(id) {
            if let Some(manifest) = manifest_for_folder(&folder) {
                out.push(RuntimeSkin {
                    id: manifest.id.clone(),
                    name: manifest.name.clone(),
                    revision: manifest.revision,
                    settings: manifest.settings.clone(),
                    pc_settings: manifest.pc_settings.clone(),
                });
            }
        }
    }
    out
}

pub fn settings_for(id: &str) -> Option<Value> {
    if id == BUILTIN_SKIN {
        return Some(json!({}));
    }
    let folder = find_skin_folder(id)?;
    let manifest = manifest_for_folder(&folder)?;
    Some(manifest.settings.unwrap_or_else(|| json!({})))
}

pub fn pc_settings_for(id: &str) -> Option<Value> {
    if id == BUILTIN_SKIN {
        return Some(default_pc_settings());
    }
    let folder = find_skin_folder(id)?;
    let manifest = manifest_for_folder(&folder)?;
    Some(manifest.pc_settings)
}

pub fn frames_for(id: &str) -> Result<SkinFrames, String> {
    if id == BUILTIN_SKIN {
        return Ok(SkinFrames {
            id: id.to_string(),
            revision: 0,
            frames: BTreeMap::new(),
            poop: None,
        });
    }
    let folder = find_skin_folder(id).ok_or_else(|| "Скин не найден".to_string())?;
    let manifest = manifest_for_folder(&folder).ok_or_else(|| "Скин повреждён".to_string())?;
    let mut frames = BTreeMap::new();
    let mut poop = None;
    for (key, file_name) in &manifest.frames {
        let path = folder.join(file_name);
        let Ok(bytes) = fs::read(&path) else {
            continue;
        };
        if !valid_png(&bytes) {
            continue;
        }
        let data = format!(
            "data:image/png;base64,{}",
            base64::engine::general_purpose::STANDARD.encode(bytes)
        );
        if key == "poop" {
            poop = Some(data);
        } else {
            frames.insert(key.clone(), data);
        }
    }
    for frame in PC_FRAME_NAMES {
        let Some(data) = extra_frame_base64(&manifest, frame) else {
            continue;
        };
        let Ok(bytes) = base64_decode(&data) else {
            continue;
        };
        if !valid_png(&bytes) {
            continue;
        }
        frames.insert(
            frame.to_string(),
            format!(
                "data:image/png;base64,{}",
                base64::engine::general_purpose::STANDARD.encode(bytes)
            ),
        );
    }
    Ok(SkinFrames {
        id: id.to_string(),
        revision: manifest.revision,
        frames,
        poop,
    })
}

fn write_manifest(folder: &Path, raw: &Value) -> Result<(), String> {
    let text = serde_json::to_string_pretty(raw)
        .map_err(|e| format!("Не удалось подготовить manifest.json: {e}"))?;
    let tmp = folder.join("manifest.json.tmp");
    fs::write(&tmp, text).map_err(|e| format!("Не удалось записать manifest.json: {e}"))?;
    fs::rename(&tmp, folder.join("manifest.json"))
        .map_err(|e| format!("Не удалось сохранить manifest.json: {e}"))
}

fn read_raw_for_update(folder: &Path, id: &str) -> Result<Value, String> {
    let manifest_path = folder.join("manifest.json");
    if manifest_path.is_file() {
        return read_json(&manifest_path).ok_or_else(|| "manifest.json повреждён".to_string());
    }
    if let Some(manifest) = synthesized_manifest(folder) {
        return Ok(manifest.raw);
    }
    Err(format!("У скина {id} нет manifest.json"))
}

pub fn import_base64(filename: &str, data: &str) -> Result<SkinInfo, String> {
    let bytes = base64_decode(data)?;
    let cursor = Cursor::new(bytes);
    let mut archive = ZipArchive::new(cursor).map_err(|_| "Это не архив .gupet".to_string())?;
    if archive.len() > MAX_ENTRIES {
        return Err("В архиве слишком много файлов".to_string());
    }
    let mut contents: BTreeMap<String, Vec<u8>> = BTreeMap::new();
    let mut total = 0usize;
    for index in 0..archive.len() {
        let entry = archive
            .by_index(index)
            .map_err(|e| format!("Не удалось прочитать архив: {e}"))?;
        if entry.is_dir() {
            continue;
        }
        let name = entry.name().to_string();
        let allowed = name == "manifest.json"
            || FRAME_NAMES
                .iter()
                .any(|frame| name == frame_file_name(frame));
        if !allowed {
            return Err(format!("Недопустимый файл в архиве: {name}"));
        }
        if contents.contains_key(&name) {
            return Err(format!("Файл {name} указан дважды"));
        }
        let limit = if name == "manifest.json" {
            MAX_MANIFEST_BYTES
        } else {
            MAX_IMAGE_BYTES
        };
        if entry.size() > limit as u64 {
            return Err("Файл в архиве превышает допустимый размер".to_string());
        }
        let mut data = Vec::new();
        entry
            .take(limit as u64 + 1)
            .read_to_end(&mut data)
            .map_err(|e| format!("Не удалось прочитать {name}: {e}"))?;
        if data.len() > limit {
            return Err("Файл в архиве превышает допустимый размер".to_string());
        }
        total += data.len();
        if total > MAX_TOTAL_BYTES {
            return Err("Архив превышает 2 МБ".to_string());
        }
        contents.insert(name, data);
    }
    let manifest_bytes = contents
        .get("manifest.json")
        .ok_or_else(|| "В архиве нет manifest.json".to_string())?;
    let manifest_text = std::str::from_utf8(manifest_bytes)
        .map_err(|_| "manifest.json должен быть в UTF-8".to_string())?;
    let manifest_value: Value = serde_json::from_str(manifest_text)
        .map_err(|_| "manifest.json содержит некорректный JSON".to_string())?;
    let manifest = parse_manifest_value(&manifest_value, true, None)?;
    for (_, file_name) in &manifest.frames {
        let data = contents
            .get(file_name)
            .ok_or_else(|| format!("Не найден файл {file_name}"))?;
        if !valid_png(data) {
            return Err(format!("Файл {file_name} должен быть PNG размером 18 × 13"));
        }
    }
    let root = skins_config_dir();
    fs::create_dir_all(&root).map_err(|e| format!("Не удалось создать папку скинов: {e}"))?;
    let folder = root.join(&manifest.id);
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let staging = root.join(format!(".{}-{stamp}.tmp", manifest.id));
    let backup = root.join(format!(".{}-{stamp}.bak", manifest.id));
    fs::create_dir_all(&staging).map_err(|e| format!("Не удалось подготовить скин: {e}"))?;
    let result = (|| -> Result<(), String> {
        for (name, data) in &contents {
            fs::write(staging.join(name), data)
                .map_err(|e| format!("Не удалось записать {name}: {e}"))?;
        }
        let mut backed_up = false;
        if folder.exists() {
            if backup.exists() {
                let _ = fs::remove_dir_all(&backup);
            }
            fs::rename(&folder, &backup)
                .map_err(|_| "Не удалось заменить установленный скин".to_string())?;
            backed_up = true;
        }
        if let Err(err) = fs::rename(&staging, &folder) {
            if backed_up && backup.exists() && !folder.exists() {
                let _ = fs::rename(&backup, &folder);
            }
            return Err(format!("Не удалось завершить импорт скина: {err}"));
        }
        if backed_up {
            let _ = fs::remove_dir_all(&backup);
        }
        Ok(())
    })();
    if staging.exists() {
        let _ = fs::remove_dir_all(&staging);
    }
    if result.is_err() && backup.exists() && !folder.exists() {
        let _ = fs::rename(&backup, &folder);
    }
    result?;
    let _ = filename;
    Ok(SkinInfo {
        id: manifest.id,
        name: manifest.name,
        author: manifest.author,
        builtin: false,
        has_settings: manifest.settings.is_some(),
        has_frames: manifest.frames.contains_key("idle"),
    })
}

pub fn delete(id: &str) -> Result<bool, String> {
    if id == BUILTIN_SKIN {
        return Ok(false);
    }
    if !valid_id(id) {
        return Err("Скин не найден".to_string());
    }
    let mut removed = false;
    for root in roots() {
        let folder = root.join(id);
        if folder.is_dir() {
            fs::remove_dir_all(&folder).map_err(|e| format!("Не удалось удалить скин: {e}"))?;
            removed = true;
        }
    }
    if !removed {
        return Err("Скин не найден".to_string());
    }
    Ok(true)
}

fn slugify(name: &str) -> String {
    let base: String = name
        .trim()
        .to_lowercase()
        .chars()
        .map(|c| {
            if c.is_ascii_lowercase() || c.is_ascii_digit() {
                c
            } else {
                '-'
            }
        })
        .collect();
    let collapsed = base
        .split('-')
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join("-");
    let trimmed = collapsed
        .trim_matches('-')
        .chars()
        .take(30)
        .collect::<String>();
    if trimmed.is_empty() {
        "skin".to_string()
    } else {
        trimmed
    }
}

fn unique_id(base: &str) -> String {
    let mut id = base.to_string();
    let mut n = 2;
    while find_skin_folder(&id).is_some() {
        let suffix = format!("-{n}");
        let keep = 40usize.saturating_sub(suffix.len());
        id = format!("{}{}", base.chars().take(keep).collect::<String>(), suffix);
        n += 1;
    }
    id
}

fn png_blank() -> Vec<u8> {
    let image = RgbaImage::new(WIDTH, HEIGHT);
    let mut out = Cursor::new(Vec::new());
    let _ = DynamicImage::ImageRgba8(image).write_to(&mut out, ImageFormat::Png);
    out.into_inner()
}

fn create_in_root(
    root: &Path,
    id: &str,
    name: &str,
    author: &str,
    frames: &BTreeMap<String, Vec<u8>>,
    settings: Value,
    pc_settings: Value,
) -> Result<(), String> {
    fs::create_dir_all(root).map_err(|e| format!("Не удалось создать папку скинов: {e}"))?;
    let folder = root.join(id);
    if folder.exists() {
        return Err("Скин уже существует".to_string());
    }
    fs::create_dir_all(&folder).map_err(|e| format!("Не удалось создать скин: {e}"))?;
    let result = (|| -> Result<(), String> {
        let mut frames_json = Map::new();
        for (key, bytes) in frames {
            let file_name = frame_file_name(key);
            fs::write(folder.join(&file_name), bytes)
                .map_err(|e| format!("Не удалось записать {file_name}: {e}"))?;
            frames_json.insert(key.clone(), Value::String(file_name));
        }
        let manifest = json!({
            "format": "GUPet",
            "formatVersion": 1,
            "version": 2,
            "width": WIDTH,
            "height": HEIGHT,
            "id": id,
            "name": name,
            "author": if author.trim().is_empty() { "Не указан" } else { author.trim() },
            "frames": frames_json,
            "settings": settings,
            "pc_settings": normalize_pc_settings(&pc_settings)
        });
        write_manifest(&folder, &manifest)
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&folder);
    }
    result
}

pub fn create(name: &str, author: &str, settings: Value) -> Result<String, String> {
    let clean_name = name.trim();
    if clean_name.is_empty()
        || clean_name.chars().count() > 40
        || clean_name.chars().any(char::is_control)
    {
        return Err("Название должно содержать от 1 до 40 печатных символов".to_string());
    }
    let id = unique_id(&slugify(clean_name));
    let mut frames = BTreeMap::new();
    frames.insert("idle".to_string(), png_blank());
    create_in_root(
        &skins_config_dir(),
        &id,
        clean_name,
        author,
        &frames,
        settings,
        default_pc_settings(),
    )?;
    Ok(id)
}

fn existing_names() -> BTreeSet<String> {
    scan_folders()
        .into_iter()
        .map(|(_, manifest)| manifest.name)
        .collect()
}

pub fn duplicate(
    source_id: &str,
    current_settings: Value,
    phrases: Value,
) -> Result<String, String> {
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    let mut id = format!("clone-{stamp}");
    while find_skin_folder(&id).is_some() {
        id = format!("clone-{}", stamp + rand::random::<u16>() as u128);
    }
    let mut settings = current_settings.clone();
    if let Some(obj) = settings.as_object_mut() {
        obj.insert("phrases".to_string(), phrases);
    }

    if source_id == BUILTIN_SKIN {
        let base_name = "GUPet";
        let taken = existing_names();
        let mut name = format!("{base_name} 2");
        let mut n = 2;
        while taken.contains(&name) {
            n += 1;
            name = format!("{base_name} {n}");
        }
        let name = name.chars().take(40).collect::<String>();
        create_in_root(
            &skins_config_dir(),
            &id,
            &name,
            "GUTeam",
            &BTreeMap::new(),
            settings,
            default_pc_settings(),
        )?;
        return Ok(id);
    }

    let folder = find_skin_folder(source_id).ok_or_else(|| "Скин не найден".to_string())?;
    let manifest = manifest_for_folder(&folder).ok_or_else(|| "Скин повреждён".to_string())?;
    let taken = existing_names();
    let mut name = format!("{} 2", manifest.name);
    let mut n = 2;
    while taken.contains(&name) {
        n += 1;
        name = format!("{} {n}", manifest.name);
    }
    let name = name.chars().take(40).collect::<String>();
    let src_settings = manifest
        .settings
        .clone()
        .unwrap_or_else(|| current_settings.clone());
    let mut frames = BTreeMap::new();
    for (key, file_name) in &manifest.frames {
        if let Ok(bytes) = fs::read(folder.join(file_name)) {
            if valid_png(&bytes) {
                frames.insert(key.clone(), bytes);
            }
        }
    }
    if frames.is_empty() {
        frames.insert("idle".to_string(), png_blank());
    }
    create_in_root(
        &skins_config_dir(),
        &id,
        &name,
        &manifest.author,
        &frames,
        src_settings,
        manifest.pc_settings.clone(),
    )?;
    Ok(id)
}

pub fn save_meta(id: &str, name: &str, author: &str) -> Result<bool, String> {
    if id == BUILTIN_SKIN {
        return Ok(false);
    }
    let clean_name = name.trim();
    if clean_name.is_empty()
        || clean_name.chars().count() > 40
        || clean_name.chars().any(char::is_control)
    {
        return Err("Название должно содержать от 1 до 40 печатных символов".to_string());
    }
    let author = author.trim();
    let author = if author.is_empty() {
        "Не указан"
    } else {
        author
    };
    let author: String = author
        .chars()
        .filter(|c| !c.is_control())
        .take(40)
        .collect();
    let folder = find_skin_folder(id).ok_or_else(|| "Скин не найден".to_string())?;
    let mut raw = read_raw_for_update(&folder, id)?;
    let Some(obj) = raw.as_object_mut() else {
        return Err("manifest.json повреждён".to_string());
    };
    obj.insert("name".to_string(), Value::String(clean_name.to_string()));
    obj.insert(
        "author".to_string(),
        Value::String(if author.trim().is_empty() {
            "Не указан".to_string()
        } else {
            author
        }),
    );
    write_manifest(&folder, &raw)?;
    Ok(true)
}

pub fn set_settings(id: &str, settings: Value) -> Result<bool, String> {
    if id == BUILTIN_SKIN {
        return Ok(false);
    }
    if !settings.is_object() {
        return Err("Настройки скина должны быть объектом".to_string());
    }
    let folder = find_skin_folder(id).ok_or_else(|| "Скин не найден".to_string())?;
    let mut raw = read_raw_for_update(&folder, id)?;
    let Some(obj) = raw.as_object_mut() else {
        return Err("manifest.json повреждён".to_string());
    };
    obj.insert("settings".to_string(), settings);
    write_manifest(&folder, &raw)?;
    Ok(true)
}

pub fn set_pc_settings(id: &str, settings: Value) -> Result<bool, String> {
    if id == BUILTIN_SKIN {
        return Ok(false);
    }
    if !settings.is_object() {
        return Err("Настройки для ПК должны быть объектом".to_string());
    }
    let folder = find_skin_folder(id).ok_or_else(|| "Скин не найден".to_string())?;
    let mut raw = read_raw_for_update(&folder, id)?;
    let Some(obj) = raw.as_object_mut() else {
        return Err("manifest.json повреждён".to_string());
    };
    let mut pc_settings = normalize_pc_settings(&settings);
    // Старое приложение/старая вкладка может не знать про extraFrames:
    // не теряем уже загруженные дополнительные кадры.
    if settings.get("extraFrames").is_none() {
        if let Some(existing) = obj
            .get("pc_settings")
            .and_then(Value::as_object)
            .and_then(|pc| pc.get("extraFrames"))
        {
            if let Some(pc) = pc_settings.as_object_mut() {
                pc.insert("extraFrames".to_string(), existing.clone());
            }
        }
    }
    obj.insert("pc_settings".to_string(), pc_settings);
    write_manifest(&folder, &raw)?;
    Ok(true)
}

pub fn snapshot_phrases(id: &str, phrases: Value) -> Result<bool, String> {
    if id == BUILTIN_SKIN {
        return Ok(false);
    }
    let folder = find_skin_folder(id).ok_or_else(|| "Скин не найден".to_string())?;
    let mut raw = read_raw_for_update(&folder, id)?;
    let Some(obj) = raw.as_object_mut() else {
        return Err("manifest.json повреждён".to_string());
    };
    let mut settings = obj
        .get("settings")
        .and_then(Value::as_object)
        .cloned()
        .unwrap_or_default();
    settings.insert("phrases".to_string(), phrases);
    obj.insert("settings".to_string(), Value::Object(settings));
    write_manifest(&folder, &raw)?;
    Ok(true)
}

pub fn write_frame_base64(id: &str, frame: &str, data: &str) -> Result<bool, String> {
    let extra = PC_FRAME_NAMES.contains(&frame);
    if !extra && !FRAME_NAMES.contains(&frame) {
        return Err("Неизвестный кадр".to_string());
    }
    let bytes = base64_decode(data)?;
    if !valid_png(&bytes) {
        return Err("Файл должен быть PNG размером 18 × 13".to_string());
    }
    if extra && data.len() > MAX_EMBEDDED_FRAME_BASE64 {
        return Err("PNG для ПК-кадра слишком большой".to_string());
    }
    let folder = find_skin_folder(id).ok_or_else(|| "Скин не найден".to_string())?;
    let mut raw = read_raw_for_update(&folder, id)?;
    let Some(obj) = raw.as_object_mut() else {
        return Err("manifest.json повреждён".to_string());
    };
    if extra {
        let mut pc_settings = normalize_pc_settings(obj.get("pc_settings").unwrap_or(&Value::Null));
        if let Some(pc) = pc_settings.as_object_mut() {
            // Загруженный ПК-кадр должен сразу применяться: включаем общий
            // переключатель и переключатель конкретного кадра.
            pc.insert("extendedFrames".to_string(), Value::Bool(true));
            let toggle = match frame {
                "cursor_hover" => Some("cursorTouch"),
                "cursor_near" => Some("cursorNear"),
                "cursor_far" => Some("cursorFar"),
                "app_open" => Some("appOpen"),
                _ => None,
            };
            if let Some(toggle) = toggle {
                pc.insert(toggle.to_string(), Value::Bool(true));
            }
            let mut frames = pc
                .get("extraFrames")
                .and_then(Value::as_object)
                .cloned()
                .unwrap_or_default();
            frames.insert(frame.to_string(), Value::String(data.to_string()));
            pc.insert("extraFrames".to_string(), Value::Object(frames));
        }
        obj.insert("pc_settings".to_string(), pc_settings);
    } else {
        fs::write(folder.join(frame_file_name(frame)), &bytes)
            .map_err(|e| format!("Не удалось записать кадр: {e}"))?;
        let mut frames = obj
            .get("frames")
            .and_then(Value::as_object)
            .cloned()
            .unwrap_or_default();
        frames.insert(frame.to_string(), Value::String(frame_file_name(frame)));
        obj.insert("frames".to_string(), Value::Object(frames));
    }
    write_manifest(&folder, &raw)?;
    Ok(true)
}

pub fn delete_frame(id: &str, frame: &str) -> Result<bool, String> {
    if frame == "idle" {
        return Ok(false);
    }
    let extra = PC_FRAME_NAMES.contains(&frame);
    if !extra && !FRAME_NAMES.contains(&frame) {
        return Err("Неизвестный кадр".to_string());
    }
    let folder = find_skin_folder(id).ok_or_else(|| "Скин не найден".to_string())?;
    let mut raw = read_raw_for_update(&folder, id)?;
    if extra {
        if let Some(obj) = raw.as_object_mut() {
            let mut pc_settings =
                normalize_pc_settings(obj.get("pc_settings").unwrap_or(&Value::Null));
            if let Some(pc) = pc_settings.as_object_mut() {
                if let Some(frames) = pc.get_mut("extraFrames").and_then(Value::as_object_mut) {
                    frames.remove(frame);
                }
            }
            obj.insert("pc_settings".to_string(), pc_settings);
            write_manifest(&folder, &raw)?;
        }
        return Ok(true);
    }
    let path = folder.join(frame_file_name(frame));
    if path.exists() {
        fs::remove_file(&path).map_err(|e| format!("Не удалось удалить кадр: {e}"))?;
    }
    if let Some(obj) = raw.as_object_mut() {
        if let Some(frames) = obj.get_mut("frames").and_then(Value::as_object_mut) {
            frames.remove(frame);
        }
        write_manifest(&folder, &raw)?;
    }
    Ok(true)
}

pub fn export(id: &str) -> Result<PathBuf, String> {
    if id == BUILTIN_SKIN {
        return Err("Встроенный скин нельзя экспортировать".to_string());
    }
    let folder = find_skin_folder(id).ok_or_else(|| "Скин не найден".to_string())?;
    let manifest = manifest_for_folder(&folder).ok_or_else(|| "Скин повреждён".to_string())?;
    let downloads = dirs::download_dir()
        .or_else(|| dirs::home_dir().map(|h| h.join("Downloads")))
        .unwrap_or_else(|| config_fallback());
    fs::create_dir_all(&downloads)
        .map_err(|e| format!("Не удалось открыть папку загрузок: {e}"))?;
    let mut path = downloads.join(format!("{}.gupet", manifest.id));
    let mut n = 2;
    while path.exists() {
        path = downloads.join(format!("{}-{n}.gupet", manifest.id));
        n += 1;
    }
    let file = fs::File::create(&path).map_err(|e| format!("Не удалось создать файл: {e}"))?;
    let mut zip = ZipWriter::new(file);
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    let files: Vec<PathBuf> = {
        let mut list = vec![folder.join("manifest.json")];
        let mut frames: Vec<PathBuf> = manifest
            .frames
            .values()
            .map(|name| folder.join(name))
            .filter(|path| path.is_file())
            .collect();
        frames.sort();
        frames.dedup();
        list.extend(frames);
        list
    };
    for path in files {
        if !path.is_file() {
            continue;
        }
        let name = path
            .file_name()
            .and_then(|s| s.to_str())
            .ok_or_else(|| "Некорректное имя файла".to_string())?;
        zip.start_file(name, options)
            .map_err(|e| format!("Не удалось упаковать скин: {e}"))?;
        let bytes = fs::read(&path).map_err(|e| format!("Не удалось прочитать {name}: {e}"))?;
        zip.write_all(&bytes)
            .map_err(|e| format!("Не удалось записать {name}: {e}"))?;
    }
    zip.finish()
        .map_err(|e| format!("Не удалось завершить архив: {e}"))?;
    Ok(path)
}

fn config_fallback() -> PathBuf {
    data_dir().join("exports")
}
