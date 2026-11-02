//! Магазин скинов GUPet.
//!
//! Список скинов берётся из GitHub Releases репозитория
//! `GreatUserTeam/petckins`: каждый прикреплённый к релизу файл `.gupet`
//! становится карточкой в магазине. Запросы выполняются в Rust через ureq,
//! поэтому одинаково работают на Linux и Windows и не зависят от CORS.

use crate::skins;
use base64::Engine;
use serde::Serialize;
use serde_json::Value;
use std::collections::BTreeSet;
use std::io::{Cursor, Read};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::Duration;
use zip::ZipArchive;

const RELEASES_URL: &str =
    "https://api.github.com/repos/GreatUserTeam/petckins/releases?per_page=100";
const USER_AGENT: &str = "GUPet/2.0 (https://github.com/GreatUserTeam/GUPet)";
const MAX_DOWNLOAD_BYTES: u64 = 8 * 1024 * 1024;
// Превью маленькие (18 × 13 PNG), но скачиваем их параллельно, чтобы
// открытие магазина не растягивалось на десятки секунд.
const PREVIEW_WORKERS: usize = 8;

#[derive(Clone, Debug, Serialize)]
pub struct RemoteSkin {
    pub id: String,
    pub name: String,
    pub author: String,
    pub description: String,
    pub download_url: String,
    pub version: String,
    pub preview: String,
    pub release_url: String,
    pub local_id: String,
    pub installed: bool,
}

#[derive(Clone, Debug, Default)]
struct ArchiveInfo {
    preview: String,
    local_id: String,
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(10))
        .timeout_read(Duration::from_secs(30))
        .timeout_write(Duration::from_secs(30))
        .build()
}

fn network_error(err: ureq::Error) -> String {
    match err {
        ureq::Error::Status(code, _) => {
            if code == 403 || code == 429 {
                "GitHub API временно ограничил запросы (лимит запросов)".to_string()
            } else {
                format!("GitHub API вернул ошибку {code}")
            }
        }
        ureq::Error::Transport(err) => format!("Нет соединения с GitHub: {err}"),
    }
}

fn collapse_whitespace(text: &str) -> String {
    text.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn prettify_name(raw: &str) -> String {
    let cleaned = raw
        .trim()
        .trim_end_matches(".gupet")
        .trim_end_matches(".GUPET")
        .replace(['_', '-', '.'], " ");
    let collapsed = collapse_whitespace(&cleaned);
    if collapsed.is_empty() {
        return "GUPet Skin".to_string();
    }
    // Красивое отображение: «black cat» -> «Black Cat». Регистр остальных
    // символов не трогаем, чтобы не ломать не-ASCII названия.
    let mut out = String::new();
    let mut capitalize = true;
    for ch in collapsed.chars() {
        if ch.is_whitespace() {
            capitalize = true;
            out.push(ch);
            continue;
        }
        if capitalize {
            out.extend(ch.to_uppercase());
            capitalize = false;
        } else {
            out.push(ch);
        }
    }
    out.chars().take(80).collect()
}

/// Разбирает тело релиза: убирает служебные строки «About:»/«Autor:»,
/// собирает описание и, если указан, автора скина.
fn parse_release_body(body: &str) -> (String, Option<String>) {
    let mut parts = Vec::new();
    let mut author: Option<String> = None;
    for line in body.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if let Some((key, value)) = line.split_once(':') {
            let value = value.trim();
            match key.trim().to_ascii_lowercase().as_str() {
                "autor" | "author" => {
                    if author.is_none() && !value.is_empty() {
                        author = Some(value.chars().take(80).collect());
                    }
                    continue;
                }
                "about" => {
                    if !value.is_empty() {
                        parts.push(value.to_string());
                    }
                    continue;
                }
                _ => {}
            }
        }
        parts.push(line.to_string());
    }
    let collapsed = collapse_whitespace(&parts.join(" "));
    if collapsed.is_empty() {
        return (
            "Скин для GUPet из официального магазина. / GUPet skin from the official store."
                .to_string(),
            author,
        );
    }
    let mut description: String = collapsed.chars().take(240).collect();
    if collapsed.chars().count() > 240 {
        description.push('…');
    }
    (description, author)
}

fn text(value: &Value, key: &str) -> String {
    value
        .get(key)
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim()
        .to_string()
}

pub fn fetch_remote_skins() -> Result<Vec<RemoteSkin>, String> {
    let response = agent()
        .get(RELEASES_URL)
        .set("User-Agent", USER_AGENT)
        .set("Accept", "application/vnd.github+json")
        .set("X-GitHub-Api-Version", "2022-11-28")
        .call()
        .map_err(network_error)?;
    let value: Value = response
        .into_json()
        .map_err(|err| format!("Некорректный ответ GitHub API: {err}"))?;
    let releases = value
        .as_array()
        .ok_or_else(|| "GitHub API вернул не список релизов".to_string())?;

    let mut out = Vec::new();
    let mut seen = BTreeSet::new();
    for release in releases {
        let version = {
            let tag = text(release, "tag_name");
            if tag.is_empty() {
                text(release, "name")
            } else {
                tag
            }
        };
        let release_name = text(release, "name");
        let release_url = {
            let url = text(release, "html_url");
            if url.is_empty() {
                "https://github.com/GreatUserTeam/petckins/releases".to_string()
            } else {
                url
            }
        };
        let (description, body_author) =
            parse_release_body(release.get("body").and_then(Value::as_str).unwrap_or(""));
        let author = body_author.unwrap_or_else(|| {
            let login = release
                .get("author")
                .and_then(|author| author.get("login"))
                .and_then(Value::as_str)
                .unwrap_or("")
                .trim()
                .to_string();
            if login.is_empty() {
                "GUTeam".to_string()
            } else {
                login
            }
        });
        let Some(assets) = release.get("assets").and_then(Value::as_array) else {
            continue;
        };
        let gupet_assets: Vec<&Value> = assets
            .iter()
            .filter(|asset| {
                text(asset, "name").to_ascii_lowercase().ends_with(".gupet")
                    && !text(asset, "browser_download_url").is_empty()
            })
            .collect();
        // Если в релизе один скин, красивое человеческое название релиза
        // («Peacock / Павлин») лучше технического имени файла.
        let release_display_name = if gupet_assets.len() == 1 && !release_name.is_empty() {
            Some(
                collapse_whitespace(&release_name)
                    .chars()
                    .take(80)
                    .collect::<String>(),
            )
        } else {
            None
        };
        for asset in gupet_assets {
            let filename = text(asset, "name");
            let download_url = text(asset, "browser_download_url");
            let id = asset
                .get("id")
                .and_then(Value::as_u64)
                .map(|id| id.to_string())
                .or_else(|| asset.get("id").and_then(Value::as_str).map(str::to_string))
                .unwrap_or_else(|| format!("{version}:{filename}"));
            if !seen.insert(id.clone()) {
                continue;
            }
            let asset_stem = filename
                .trim_end_matches(".gupet")
                .trim_end_matches(".GUPET")
                .trim();
            let name = release_display_name
                .clone()
                .filter(|name| !name.is_empty())
                .unwrap_or_else(|| prettify_name(asset_stem));
            out.push(RemoteSkin {
                id,
                name,
                author: author.clone(),
                description: description.clone(),
                download_url,
                version: if version.is_empty() {
                    "—".to_string()
                } else {
                    version.clone()
                },
                preview: String::new(),
                release_url: release_url.clone(),
                local_id: String::new(),
                installed: false,
            });
        }
    }
    enrich_with_previews(&mut out);
    Ok(out)
}

/// Достаёт `idle.png` и id скина прямо из скачанного `.gupet`.
/// Ошибка превью не должна ломать весь магазин — в этом случае карточка
/// просто останется без картинки.
fn inspect_archive(bytes: &[u8]) -> ArchiveInfo {
    let mut info = ArchiveInfo::default();
    let Ok(mut archive) = ZipArchive::new(Cursor::new(bytes)) else {
        return info;
    };
    if archive.len() > skins::MAX_ENTRIES {
        return info;
    }
    if let Ok(entry) = archive.by_name("idle.png") {
        if entry.size() <= skins::MAX_IMAGE_BYTES as u64 {
            let mut data = Vec::new();
            if entry
                .take(skins::MAX_IMAGE_BYTES as u64 + 1)
                .read_to_end(&mut data)
                .is_ok()
                && skins::valid_png(&data)
            {
                info.preview = format!(
                    "data:image/png;base64,{}",
                    base64::engine::general_purpose::STANDARD.encode(&data)
                );
            }
        }
    }
    if let Ok(entry) = archive.by_name("manifest.json") {
        if entry.size() <= skins::MAX_MANIFEST_BYTES as u64 {
            let mut data = Vec::new();
            if entry
                .take(skins::MAX_MANIFEST_BYTES as u64 + 1)
                .read_to_end(&mut data)
                .is_ok()
            {
                if let Ok(value) = serde_json::from_slice::<Value>(&data) {
                    let id = value.get("id").and_then(Value::as_str).unwrap_or("").trim();
                    if skins::valid_id(id) {
                        info.local_id = id.to_string();
                    }
                }
            }
        }
    }
    info
}

fn enrich_with_previews(out: &mut [RemoteSkin]) {
    if out.is_empty() {
        return;
    }
    let jobs: Vec<(usize, String)> = out
        .iter()
        .enumerate()
        .map(|(index, skin)| (index, skin.download_url.clone()))
        .collect();
    let next = AtomicUsize::new(0);
    let results: Mutex<Vec<Option<ArchiveInfo>>> =
        Mutex::new((0..out.len()).map(|_| None).collect());
    let workers = jobs.len().min(PREVIEW_WORKERS);

    std::thread::scope(|scope| {
        for _ in 0..workers {
            scope.spawn(|| {
                let agent = agent();
                loop {
                    let index = next.fetch_add(1, Ordering::Relaxed);
                    if index >= jobs.len() {
                        break;
                    }
                    let (slot, url) = &jobs[index];
                    if let Ok(bytes) = download_bytes_with_agent(&agent, url) {
                        let info = inspect_archive(&bytes);
                        if let Ok(mut results) = results.lock() {
                            results[*slot] = Some(info);
                        }
                    }
                }
            });
        }
    });

    let results = results.into_inner().unwrap_or_default();
    for (skin, archive) in out.iter_mut().zip(results.into_iter()) {
        let Some(archive) = archive else {
            continue;
        };
        skin.preview = archive.preview;
        skin.local_id = archive.local_id;
        if !skin.local_id.is_empty() {
            skin.installed = skins::find_skin_folder(&skin.local_id).is_some();
        }
    }
}

fn allowed_host(url: &str) -> bool {
    let rest = match url.strip_prefix("https://") {
        Some(rest) => rest,
        None => return false,
    };
    let host = rest
        .split(['/', '?', '#'])
        .next()
        .unwrap_or("")
        .split('@')
        .next_back()
        .unwrap_or("")
        .split(':')
        .next()
        .unwrap_or("")
        .to_ascii_lowercase();
    matches!(
        host.as_str(),
        "github.com"
            | "objects.githubusercontent.com"
            | "github-releases.githubusercontent.com"
            | "raw.githubusercontent.com"
    )
}

pub fn download_skin(url: &str) -> Result<Vec<u8>, String> {
    download_bytes_with_agent(&agent(), url)
}

fn download_bytes_with_agent(agent: &ureq::Agent, url: &str) -> Result<Vec<u8>, String> {
    if !allowed_host(url) {
        return Err("Недопустимая ссылка на скин".to_string());
    }
    let response = agent
        .get(url)
        .set("User-Agent", USER_AGENT)
        .call()
        .map_err(network_error)?;
    let mut bytes = Vec::new();
    response
        .into_reader()
        .take(MAX_DOWNLOAD_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|err| format!("Не удалось скачать скин: {err}"))?;
    if bytes.len() as u64 > MAX_DOWNLOAD_BYTES {
        return Err("Файл скина слишком большой".to_string());
    }
    if bytes.is_empty() {
        return Err("GitHub вернул пустой файл скина".to_string());
    }
    Ok(bytes)
}
