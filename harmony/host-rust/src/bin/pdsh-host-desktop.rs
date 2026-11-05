// SPDX-License-Identifier: MIT
//! Desktop smoke launcher for the Harmony host.
//!
//! This is a development aid, not the device integration: it starts the same
//! `Host` on macOS/Linux so `/boot-config`, `/health`, static assets, the
//! overlay and the WebSocket can be exercised before cross-compiling for
//! `aarch64-unknown-linux-ohos`. Press Enter to stop.

use std::io::Write;
use std::path::PathBuf;

fn main() {
    let mut args = std::env::args().skip(1);
    let mut root = PathBuf::from("./.pdsh-host");
    let mut assets = PathBuf::from("./.pdsh-assets");
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--root" => root = args.next().map(PathBuf::from).unwrap_or(root),
            "--assets" => assets = args.next().map(PathBuf::from).unwrap_or(assets),
            "--help" | "-h" => {
                println!("usage: pdsh-host-desktop [--root DIR] [--assets DIR]");
                return;
            }
            other => {
                eprintln!("unknown argument {other}");
                std::process::exit(2);
            }
        }
    }
    match pdsh_host::Host::start(&root, &assets) {
        Ok(host) => {
            let info = host.info.clone();
            let start = serde_json::json!({
                "port": info.port,
                "token": info.token,
                "bootId": info.boot_id,
                "sessionEpoch": info.session_epoch,
                "ackedMseq": info.acked_mseq,
                "recoveredBytes": info.recovered_bytes
            });
            println!(
                "{}",
                serde_json::to_string_pretty(&start)
                    .unwrap_or_else(|_| "{\"error\":\"print_failed\"}".to_owned())
            );
            println!(
                "health: GET /health?token={}  boot: GET /boot-config?token={}",
                info.token, info.token
            );
            print!("[Enter] to stop: ");
            let _ = std::io::stdout().flush();
            let mut line = String::new();
            let _ = std::io::stdin().read_line(&mut line);
            host.stop();
            println!("stopped");
        }
        Err(error) => {
            eprintln!("host start failed: {error}");
            std::process::exit(1);
        }
    }
}
