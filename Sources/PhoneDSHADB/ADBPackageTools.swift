// SPDX-License-Identifier: MIT
import Foundation
import PhoneDSHAgentCore

/// Structured entry points for pm/am and bounded diagnostics. The device's
/// own help remains available because OEMs and Android releases differ.
enum ADBPackageTools {
    static let package = ADBTools.property("string", "Installed application ID, e.g. com.example.app; not an APK filename.")
    static let user = ADBTools.property("integer", "Android user/profile ID; omitted means the current foreground user. Use android_list_users to discover IDs.")
    static func choice(_ values: [String], _ description: String) -> JSONValue {
        .object(["type": .string("string"), "enum": .array(values.map(JSONValue.string)), "description": .string(description)])
    }
    static let descriptors: [JSONValue] = [
        ADBTools.tool("android_list_packages", "List installed Android apps by package ID with APK paths and installer. This lists installed apps, not downloaded APK files.", [
            "filter": ADBTools.property("string", "Optional package-name substring."), "userId": user,
            "kind": choice(["all", "third_party", "system"], "Default all."),
            "state": choice(["all", "enabled", "disabled"], "Default all."),
            "includeUninstalled": ADBTools.property("boolean", "Include retained package records for uninstalled apps; default false.")]),
        ADBTools.tool("android_package_info", "Show APK paths (including splits) and package metadata: version, permissions, activities and per-user state.",
                      ["packageName": package, "userId": user], required: ["packageName"]),
        ADBTools.tool("android_install_apk", "Install a single APK or update an app. workspace streams an iPhone workspace APK over ADB; device reads an APK already on Android. Not an AAB/APKS/XAPK or split-APK set. Inspect state after an interruption before retrying.", [
            "apkPath": ADBTools.property("string", "workspace: absolute /dsh/workspace/...apk path. device: absolute Android path, preferably /data/local/tmp/...apk."),
            "source": choice(["workspace", "device"], "Required: distinguishes the two filesystems."),
            "sha256": ADBTools.property("string", "Required for workspace: 64 hex characters from sha256sum in the Worker. Prevents installing stale mirrored bytes. Single APK limit 128 MiB."),
            "userId": user,
            "replaceExisting": ADBTools.property("boolean", "Default true (-r); false uses pm -R on supported Android versions."),
            "allowTest": ADBTools.property("boolean", "Allow test-only APKs (-t); default false."),
            "grantPermissions": ADBTools.property("boolean", "Grant runtime permissions at install (-g); default false.")], required: ["apkPath", "source"]),
        ADBTools.tool("android_uninstall_app", "Uninstall a package for the current/specified Android user. App data is deleted unless keepData=true. System image APKs remain on disk. Does not delete a downloaded installer file.",
                      ["packageName": package, "userId": user, "keepData": ADBTools.property("boolean", "Keep app data/cache; default false.")], required: ["packageName"]),
        ADBTools.tool("android_force_stop", "Force-stop an app's processes for the selected user; preserves installed app and data.",
                      ["packageName": package, "userId": user], required: ["packageName"]),
        ADBTools.tool("android_clear_app_data", "Delete an app's data and cache, including login state, for the selected user. Leaves the app installed.",
                      ["packageName": package, "userId": user], required: ["packageName"]),
        ADBTools.tool("android_set_app_enabled", "Enable or disable an installed app for the selected user (pm enable/disable-user).",
                      ["packageName": package, "userId": user, "enabled": ADBTools.property("boolean", "true enables; false disables.")], required: ["packageName", "enabled"]),
        ADBTools.tool("android_install_existing_app", "Restore an existing system/shared package for a user using pm install-existing. Cannot restore an APK that is no longer on the device.",
                      ["packageName": package, "userId": user], required: ["packageName"]),
        ADBTools.tool("android_app_permission", "Grant or revoke a runtime permission declared by an app. Android rejects signature/system permissions and policy-restricted changes.",
                      ["packageName": package, "userId": user, "permission": ADBTools.property("string", "Full permission name, e.g. android.permission.CAMERA."),
                       "action": choice(["grant", "revoke"], "Permission operation.")], required: ["packageName", "permission", "action"]),
        ADBTools.tool("android_list_users", "List Android users/profiles and report the current foreground user."),
        ADBTools.tool("android_open_uri", "Open an Android VIEW intent (web URL or deep link) for the selected user.",
                      ["uri": ADBTools.property("string", "URI with a scheme, up to 2048 UTF-8 bytes."), "userId": user,
                       "packageName": package], required: ["uri"]),
        ADBTools.tool("android_logcat", "Read recent logcat lines, then exit; no infinite log stream. Logs may contain app/user data.", [
            "lines": ADBTools.property("integer", "Last 1–2000 lines; default 200."),
            "buffer": choice(["main", "system", "crash", "events", "all"], "Default main."),
            "priority": choice(["V", "D", "I", "W", "E", "F"], "Minimum priority; default I.")]),
        ADBTools.tool("android_device_info", "Read model, OS/API, display, battery and storage diagnostics."),
        ADBTools.tool("android_list_apk_files", "Find APK installer files under an Android directory. Separate from listing installed packages; Android filesystem permissions apply.",
                      ["directory": ADBTools.property("string", "Absolute Android directory; default /sdcard/Download.")]),
        ADBTools.tool("android_delete_apk_file", "Delete one APK installer file on Android by its exact absolute path. This does not uninstall an app. Do not target installed package paths under /data/app or system partitions.",
                      ["apkPath": ADBTools.property("string", "Exact absolute .apk file path; wildcards are not expanded.")], required: ["apkPath"]),
        ADBTools.tool("android_command_help", "Read command help from this Android device to discover more supported options. Use android_shell for commands without a dedicated tool; omit the desktop 'adb shell' prefix.",
                      ["topic": choice(["package", "activity", "input", "settings", "files", "logcat", "services"], "Default package (pm help).")]),
    ]

    static func plan(name: String, args: [String: JSONValue]) throws -> ADBTools.Plan {
        let a = Arguments(values: args)
        let user = try a.user()
        func pkg() throws -> String { ADBTools.quote(try a.identifier("packageName")) }
        func done(_ command: String, success: Bool = false, timeout: TimeInterval = 30) -> ADBTools.Plan {
            ADBTools.Plan(command: command, timeout: timeout, requiresSuccess: success)
        }
        switch name {
        case "android_list_packages":
            var command = "pm list packages -f -i" + user
            switch try a.choice("kind", ["all", "third_party", "system"], fallback: "all") {
            case "third_party": command += " -3"
            case "system": command += " -s"
            default: break
            }
            switch try a.choice("state", ["all", "enabled", "disabled"], fallback: "all") {
            case "enabled": command += " -e"
            case "disabled": command += " -d"
            default: break
            }
            if try a.bool("includeUninstalled") { command += " -u" }
            if args["filter"] != nil {
                let filter = try a.text("filter", limit: 255)
                guard filter.range(of: "^[A-Za-z0-9_.]+$", options: .regularExpression) != nil else { throw ADBFailure("Invalid package filter") }
                command += " " + ADBTools.quote(filter)
            }
            return done(command)
        case "android_package_info": return done("pm path" + user + " " + (try pkg()) + " && dumpsys package " + (try pkg()))
        case "android_install_apk":
            let source = try a.choice("source", ["workspace", "device"])
            let path = try a.path("apkPath", apk: true)
            var command = "pm install" + user + (try a.bool("replaceExisting", fallback: true) ? " -r" : " -R")
            if try a.bool("allowTest") { command += " -t" }
            if try a.bool("grantPermissions") { command += " -g" }
            if source == "workspace" {
                guard path.hasPrefix("/dsh/workspace/") else { throw ADBFailure("Workspace APK must be under /dsh/workspace/") }
                let hash = try a.text("sha256", limit: 64).lowercased()
                guard hash.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else { throw ADBFailure("sha256 must contain 64 hexadecimal characters") }
                return ADBTools.Plan(command: command, timeout: 120, workspaceAPK: path, sha256: hash, requiresSuccess: true)
            }
            guard args["sha256"] == nil else { throw ADBFailure("sha256 verification is supported for workspace APKs only") }
            return done(command + " " + ADBTools.quote(path), success: true, timeout: 120)
        case "android_uninstall_app": return done("pm uninstall" + (try a.bool("keepData") ? " -k" : "") + user + " " + (try pkg()), success: true, timeout: 120)
        case "android_force_stop": return done("am force-stop" + user + " " + (try pkg()))
        case "android_clear_app_data": return done("pm clear" + user + " " + (try pkg()), success: true)
        case "android_set_app_enabled": return done("pm " + (try a.bool("enabled", required: true) ? "enable" : "disable-user") + user + " " + (try pkg()))
        case "android_install_existing_app": return done("pm install-existing" + user + " " + (try pkg()))
        case "android_app_permission": return done("pm " + (try a.choice("action", ["grant", "revoke"])) + user + " " + (try pkg()) + " " + ADBTools.quote(try a.identifier("permission")))
        case "android_list_users": return done("pm list users && am get-current-user")
        case "android_open_uri":
            let uri = try a.text("uri", limit: 2048)
            guard let scheme = URLComponents(string: uri)?.scheme, !scheme.isEmpty else { throw ADBFailure("uri must include a scheme") }
            return done("am start" + user + " -a android.intent.action.VIEW -d " + ADBTools.quote(uri) + (args["packageName"] == nil ? "" : " -p " + (try pkg())))
        case "android_logcat":
            return done("logcat -d -v threadtime -t \(try a.integer("lines", range: 1...2000, fallback: 200)) -b " + (try a.choice("buffer", ["main", "system", "crash", "events", "all"], fallback: "main")) + " " + ADBTools.quote("*:" + (try a.choice("priority", ["V", "D", "I", "W", "E", "F"], fallback: "I"))))
        case "android_device_info": return done("getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; wm size; wm density; dumpsys battery; df -h /data /sdcard")
        case "android_list_apk_files": return done("find " + ADBTools.quote(args["directory"] == nil ? "/sdcard/Download" : try a.path("directory")) + " -type f -iname '*.apk'")
        case "android_delete_apk_file":
            let path = try a.path("apkPath", apk: true)
            guard ["/sdcard/", "/storage/", "/data/local/tmp/"].contains(where: path.hasPrefix) else {
                throw ADBFailure("Delete APK files only in shared storage or /data/local/tmp; uninstall apps by packageName")
            }
            return done("rm -- " + ADBTools.quote(path))
        case "android_command_help":
            let topic = try a.choice("topic", ["package", "activity", "input", "settings", "files", "logcat", "services"], fallback: "package")
            return done(["package": "pm help", "activity": "am help", "input": "input", "settings": "settings help",
                         "files": "ls --help; find --help; cat --help; rm --help; df --help", "logcat": "logcat --help", "services": "dumpsys -l; cmd -l"][topic]!)
        default: throw ADBFailure("Unknown Android tool")
        }
    }

    struct Arguments {
        let values: [String: JSONValue]
        func text(_ key: String, limit: Int = 1024) throws -> String {
            guard let value = values[key]?.text, !value.isEmpty, value.utf8.count <= limit,
                  !value.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
                throw ADBFailure("\(key) must be nonempty text, at most \(limit) bytes, without control characters")
            }
            return value
        }
        func bool(_ key: String, fallback: Bool = false, required: Bool = false) throws -> Bool {
            if values[key] == nil && !required { return fallback }
            guard case .bool(let value) = values[key] else { throw ADBFailure("\(key) must be a boolean") }
            return value
        }
        func integer(_ key: String, range: ClosedRange<Int>, fallback: Int) throws -> Int {
            if values[key] == nil { return fallback }
            guard case .number(let n) = values[key], n.isFinite, n.rounded() == n,
                  n >= Double(range.lowerBound), n <= Double(range.upperBound) else { throw ADBFailure("\(key) must be an integer in \(range)") }
            return Int(n)
        }
        func choice(_ key: String, _ allowed: [String], fallback: String? = nil) throws -> String {
            if values[key] == nil, let fallback { return fallback }
            let value = try text(key)
            guard allowed.contains(value) else { throw ADBFailure("Invalid \(key); expected \(allowed.joined(separator: ", "))") }
            return value
        }
        func identifier(_ key: String) throws -> String {
            let value = try text(key, limit: 255)
            guard value.range(of: "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*$", options: .regularExpression) != nil else { throw ADBFailure("Invalid \(key)") }
            return value
        }
        func user() throws -> String { values["userId"] == nil ? " --user current" : " --user \(try integer("userId", range: 0...2147483647, fallback: 0))" }
        func path(_ key: String, apk: Bool = false) throws -> String {
            let value = try text(key)
            guard value.hasPrefix("/"), !value.split(separator: "/").contains(".."),
                  !apk || value.lowercased().hasSuffix(".apk") else { throw ADBFailure("\(key) must be an absolute \(apk ? ".apk " : "")path without .. components") }
            return value
        }
    }
}
