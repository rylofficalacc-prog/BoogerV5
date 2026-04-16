// Booger Client Launcher — Tauri Rust backend
//
// Handles:
// 1. Discord Rich Presence IPC bridge (listens on localhost:29384)
// 2. System info detection (RAM, CPU cores, Java path)
// 3. Minecraft classpath construction
// 4. Cloud sync API client (Booger API)
// 5. JVM process management (spawn, monitor, kill)

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager, State};

// ─── Discord IPC Bridge ──────────────────────────────────────────────────────

#[derive(Deserialize, Debug)]
struct PresenceUpdate {
    #[serde(rename = "type")]
    msg_type:         String,
    state:            Option<String>,
    details:          Option<String>,
    large_image_key:  Option<String>,
    large_image_text: Option<String>,
    small_image_key:  Option<String>,
    small_image_text: Option<String>,
    start_timestamp:  Option<i64>,
    party_size:       Option<i32>,
    party_max:        Option<i32>,
}

struct DiscordBridgeState {
    // discord_sdk would go here in production
    // Using discord-rpc crate: discord_rpc_client::Client
    last_presence: Option<PresenceUpdate>,
}

/// Start the Discord IPC bridge server.
/// The game connects to localhost:29384 and sends JSON presence updates.
fn start_discord_bridge(app: AppHandle) {
    thread::Builder::new()
        .name("booger-discord-bridge".into())
        .spawn(move || {
            let listener = match TcpListener::bind("127.0.0.1:29384") {
                Ok(l) => l,
                Err(e) => {
                    eprintln!("[DiscordBridge] Failed to bind IPC port: {}", e);
                    return;
                }
            };

            println!("[DiscordBridge] Listening on 127.0.0.1:29384");

            for stream in listener.incoming() {
                match stream {
                    Ok(stream) => {
                        let app_clone = app.clone();
                        thread::spawn(move || handle_game_connection(stream, app_clone));
                    }
                    Err(e) => eprintln!("[DiscordBridge] Connection error: {}", e),
                }
            }
        })
        .expect("Failed to spawn discord bridge thread");
}

fn handle_game_connection(stream: TcpStream, _app: AppHandle) {
    let peer = stream.peer_addr().ok();
    println!("[DiscordBridge] Game connected from {:?}", peer);

    stream
        .set_read_timeout(Some(Duration::from_secs(30)))
        .ok();

    let reader = BufReader::new(&stream);

    for line in reader.lines() {
        match line {
            Ok(json_str) => {
                if let Ok(update) = serde_json::from_str::<PresenceUpdate>(&json_str) {
                    handle_presence_update(update);
                }
            }
            Err(e) => {
                println!("[DiscordBridge] Game disconnected: {}", e);
                break;
            }
        }
    }

    println!("[DiscordBridge] Game connection closed");
}

fn handle_presence_update(update: PresenceUpdate) {
    match update.msg_type.as_str() {
        "UPDATE_PRESENCE" => {
            // In production: call discord_rpc_client to update presence
            // discord_client.set_activity(|act| {
            //     act.state(update.state.unwrap_or_default())
            //        .details(update.details.unwrap_or_default())
            //        ...
            // }).ok();
            println!(
                "[DiscordBridge] Presence update: {:?} | {:?}",
                update.state, update.details
            );
        }
        "CLEAR_PRESENCE" => {
            // discord_client.clear_activity().ok();
            println!("[DiscordBridge] Presence cleared");
        }
        _ => {
            eprintln!("[DiscordBridge] Unknown message type: {}", update.msg_type);
        }
    }
}

// ─── System Info Detection ────────────────────────────────────────────────────

#[derive(Serialize, Debug)]
struct SystemInfo {
    total_ram_mb:   u64,
    cpu_cores:      usize,
    os:             String,
    java_version:   Option<String>,
    java_path:      Option<String>,
}

#[tauri::command]
async fn detect_system_info(java_path: String) -> Result<SystemInfo, String> {
    let total_ram_mb = get_total_ram_mb();
    let cpu_cores = num_cpus::get();
    let os = detect_os();
    let (java_version, resolved_path) = detect_java_version(&java_path);

    Ok(SystemInfo {
        total_ram_mb,
        cpu_cores,
        os,
        java_version,
        java_path: resolved_path,
    })
}

fn get_total_ram_mb() -> u64 {
    // Platform-specific RAM detection
    #[cfg(target_os = "windows")]
    {
        use std::mem;
        // Would use GlobalMemoryStatusEx via winapi crate
        // Placeholder: return 8GB
        8192
    }

    #[cfg(target_os = "macos")]
    {
        let output = Command::new("sysctl")
            .args(["-n", "hw.memsize"])
            .output()
            .ok();
        output
            .and_then(|o| String::from_utf8(o.stdout).ok())
            .and_then(|s| s.trim().parse::<u64>().ok())
            .map(|bytes| bytes / (1024 * 1024))
            .unwrap_or(8192)
    }

    #[cfg(target_os = "linux")]
    {
        // Parse /proc/meminfo
        std::fs::read_to_string("/proc/meminfo")
            .ok()
            .and_then(|content| {
                content.lines()
                    .find(|l| l.starts_with("MemTotal:"))
                    .and_then(|l| l.split_whitespace().nth(1))
                    .and_then(|s| s.parse::<u64>().ok())
                    .map(|kb| kb / 1024) // kB → MB
            })
            .unwrap_or(8192)
    }

    #[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
    { 8192 }
}

fn detect_os() -> String {
    if cfg!(target_os = "windows") { "windows".into() }
    else if cfg!(target_os = "macos") { "macos".into() }
    else { "linux".into() }
}

fn detect_java_version(java_path: &str) -> (Option<String>, Option<String>) {
    let effective_path = if java_path.is_empty() {
        // Try to find java in PATH
        which_java().unwrap_or_default()
    } else {
        java_path.to_string()
    };

    if effective_path.is_empty() {
        return (None, None);
    }

    let output = Command::new(&effective_path)
        .arg("-version")
        .stderr(Stdio::piped()) // java -version writes to stderr
        .output()
        .ok();

    let version = output.and_then(|o| {
        let stderr = String::from_utf8_lossy(&o.stderr).to_string();
        // Parse: `openjdk version "21.0.4" 2024-07-16`
        stderr.lines()
            .find(|l| l.contains("version"))
            .and_then(|l| {
                let start = l.find('"')? + 1;
                let end = l.rfind('"')?;
                Some(l[start..end].to_string())
            })
    });

    (version, Some(effective_path))
}

fn which_java() -> Option<String> {
    let cmd = if cfg!(target_os = "windows") { "where" } else { "which" };
    let output = Command::new(cmd).arg("java").output().ok()?;
    let path = String::from_utf8(output.stdout).ok()?.trim().to_string();
    if path.is_empty() { None } else { Some(path) }
}

// ─── Classpath Builder ────────────────────────────────────────────────────────

#[tauri::command]
async fn build_classpath(
    game_dir:          String,
    minecraft_version: String,
    fabric_version:    String,
) -> Result<String, String> {
    let mut jars: Vec<String> = Vec::new();

    // Minecraft version JAR
    let mc_jar = format!("{}/versions/{}/{}.jar", game_dir, minecraft_version, minecraft_version);
    if std::path::Path::new(&mc_jar).exists() {
        jars.push(mc_jar);
    }

    // Fabric loader JAR
    let fabric_jar = format!(
        "{}/libraries/net/fabricmc/fabric-loader/{}/fabric-loader-{}.jar",
        game_dir, fabric_version, fabric_version
    );
    if std::path::Path::new(&fabric_jar).exists() {
        jars.push(fabric_jar);
    }

    // All JARs in libraries directory (Minecraft dependencies)
    let libraries_dir = format!("{}/libraries", game_dir);
    if let Ok(entries) = collect_jars_recursive(&libraries_dir) {
        jars.extend(entries);
    }

    // Booger Client mod JAR
    let booger_jar = format!("{}/mods/booger-0.1.0.jar", game_dir);
    if std::path::Path::new(&booger_jar).exists() {
        jars.push(booger_jar);
    }

    let separator = if cfg!(target_os = "windows") { ";" } else { ":" };
    Ok(jars.join(separator))
}

fn collect_jars_recursive(dir: &str) -> std::io::Result<Vec<String>> {
    let mut jars = Vec::new();
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            if let Ok(sub_jars) = collect_jars_recursive(path.to_str().unwrap_or("")) {
                jars.extend(sub_jars);
            }
        } else if path.extension().map(|e| e == "jar").unwrap_or(false) {
            if let Some(s) = path.to_str() {
                jars.push(s.to_string());
            }
        }
    }
    Ok(jars)
}

// ─── Fabric Install Verification ──────────────────────────────────────────────

#[tauri::command]
async fn ensure_fabric_installed(
    minecraft_version: String,
    fabric_version:    String,
    game_dir:          String,
) -> Result<bool, String> {
    let loader_path = format!(
        "{}/libraries/net/fabricmc/fabric-loader/{}/fabric-loader-{}.jar",
        game_dir, fabric_version, fabric_version
    );

    if std::path::Path::new(&loader_path).exists() {
        return Ok(true);
    }

    // Download Fabric installer and run it
    // In production: download from https://maven.fabricmc.net/ directly
    // For now: return error instructing manual install
    Err(format!(
        "Fabric {fabric_version} for MC {minecraft_version} not found at {loader_path}. \
         Please install Fabric manually from https://fabricmc.net/use/installer/"
    ))
}

// ─── Booger Mod JAR Verification ─────────────────────────────────────────────

#[tauri::command]
async fn ensure_booger_mod_jar(
    game_dir:    String,
    mod_version: String,
) -> Result<bool, String> {
    let jar_path = format!("{}/mods/booger-{}.jar", game_dir, mod_version);

    if std::path::Path::new(&jar_path).exists() {
        return Ok(true);
    }

    Err(format!(
        "Booger Client mod JAR not found at {}. \
         Run 'gradle build' and copy the output JAR to the mods folder.",
        jar_path
    ))
}

// ─── Cloud Sync API ───────────────────────────────────────────────────────────

#[derive(Serialize, Deserialize, Debug)]
struct ProfileSyncRequest {
    profile_name: String,
    profile_data: String, // JSON blob
    client_version: String,
}

#[tauri::command]
async fn sync_profile_to_cloud(
    profile_name: String,
    profile_json: String,
    auth_token:   String,
) -> Result<bool, String> {
    // Cloud sync endpoint (Phase 5 — stub until API is live)
    let _request = ProfileSyncRequest {
        profile_name,
        profile_data: profile_json,
        client_version: "0.1.0".into(),
    };

    // In production:
    // reqwest::Client::new()
    //     .post("https://api.boogerclient.dev/v1/profiles/sync")
    //     .header("Authorization", format!("Bearer {}", auth_token))
    //     .json(&request)
    //     .send()
    //     .await
    //     .map_err(|e| e.to_string())?;

    println!("[CloudSync] Profile sync stub called (API endpoint pending)");
    Ok(true)
}

// ─── Tauri Entry Point ────────────────────────────────────────────────────────

fn main() {
    tauri::Builder::default()
        .setup(|app| {
            // Start Discord IPC bridge on app startup
            start_discord_bridge(app.handle().clone());
            println!("[Booger Launcher] Started successfully");
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            detect_system_info,
            build_classpath,
            ensure_fabric_installed,
            ensure_booger_mod_jar,
            sync_profile_to_cloud,
        ])
        .run(tauri::generate_context!())
        .expect("Failed to start Booger Launcher");
}
