// Trawl's global shortcut on macOS: a long-lived helper that registers ONE system-wide hotkey with Carbon's RegisterEventHotKey and
// reports each press. That API needs no Accessibility permission, no Input Monitoring and no event tap: the system itself delivers
// the key to the registering process. (A CGEventTap or NSEvent global monitor would need those permissions; this does not.)
//
// stdin, one command per line:        stdout, one line per answer or event:
//   reg <keyCode> <carbonMods>          ok | taken | err <OSStatus>
//   unreg                               ok
//   syshot                              sys <keyCode> <carbonMods> ... then: end   (enabled system shortcuts the person has set)
//   quit                                (exits)
//                                       press   (the registered shortcut was pressed)
// `hotkey-mac --selftest` registers an unusual combination, checks the duplicate is refused, delivers one synthetic press to its own
// handler, unregisters at once, prints what AXIsProcessTrusted says (no permission was asked for) and exits 0 on success.
// Nothing here touches the network.
import Foundation
import AppKit
import Carbon.HIToolbox
import ApplicationServices

setvbuf(stdout, nil, _IOLBF, 0)

let signature: OSType = 0x54524C57  // 'TRLW'
var hotKeyRef: EventHotKeyRef? = nil
var pressed: () -> Void = { print("press") }

func say(_ s: String) { print(s) }

let handler: EventHandlerUPP = { (_, event, _) -> OSStatus in
    var id = EventHotKeyID()
    GetEventParameter(event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID), nil, MemoryLayout<EventHotKeyID>.size, nil, &id)
    if id.signature == signature { pressed() }
    return noErr
}

func installHandler() -> OSStatus {
    var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
    return InstallEventHandler(GetApplicationEventTarget(), handler, 1, &spec, nil, nil)
}

func register(_ code: UInt32, _ mods: UInt32) -> String {
    if let r = hotKeyRef { UnregisterEventHotKey(r); hotKeyRef = nil }
    var ref: EventHotKeyRef? = nil
    let id = EventHotKeyID(signature: signature, id: 1)
    let st = RegisterEventHotKey(code, mods, id, GetApplicationEventTarget(), 0, &ref)
    if st == noErr { hotKeyRef = ref; return "ok" }
    return st == OSStatus(eventHotKeyExistsErr) ? "taken" : "err \(st)"
}

func unregister() { if let r = hotKeyRef { UnregisterEventHotKey(r); hotKeyRef = nil } }

// The system shortcuts the person has changed or enabled (System Settings > Keyboard > Keyboard Shortcuts), as Carbon codes.
// Defaults that were never touched are not in this file; Trawl's own table covers the documented ones.
func systemHotkeys() -> [String] {
    guard let all = UserDefaults(suiteName: "com.apple.symbolichotkeys")?.dictionary(forKey: "AppleSymbolicHotKeys") else { return [] }
    var out: [String] = []
    for (_, v) in all {
        guard let d = v as? [String: Any], (d["enabled"] as? Bool) == true,
              let value = d["value"] as? [String: Any], let p = value["parameters"] as? [Any], p.count >= 3,
              let code = p[1] as? Int, let flags = p[2] as? Int, code >= 0, code < 128 else { continue }
        var m = 0
        if flags & (1 << 20) != 0 { m |= cmdKey }
        if flags & (1 << 17) != 0 { m |= shiftKey }
        if flags & (1 << 19) != 0 { m |= optionKey }
        if flags & (1 << 18) != 0 { m |= controlKey }
        out.append("sys \(code) \(m)")
    }
    return out
}

if CommandLine.arguments.contains("--selftest") {
    var fails = 0
    func check(_ name: String, _ ok: Bool, _ detail: String = "") { print((ok ? "PASS " : "FAIL ") + name + (detail.isEmpty ? "" : " (\(detail))")); if !ok { fails += 1 } }
    check("handler installed", installHandler() == noErr)
    // Control+Option+Command+Shift+F19: a combination no keyboard shortcut or launcher uses.
    let code: UInt32 = 80, mods = UInt32(controlKey | optionKey | cmdKey | shiftKey)
    var count = 0
    pressed = { count += 1 }
    check("register", register(code, mods) == "ok")
    var dup: EventHotKeyRef? = nil
    let dupStatus = RegisterEventHotKey(code, mods, EventHotKeyID(signature: signature, id: 2), GetApplicationEventTarget(), 0, &dup)
    check("second registration of the same key is refused", dupStatus == OSStatus(eventHotKeyExistsErr), "status \(dupStatus)")
    if let d = dup { UnregisterEventHotKey(d) }
    // One synthetic press through the same dispatcher a real press uses. This proves the handler wiring; the key itself is the OS's job.
    var ev: EventRef? = nil
    CreateEvent(nil, OSType(kEventClassKeyboard), UInt32(kEventHotKeyPressed), 0, 0, &ev)
    var id = EventHotKeyID(signature: signature, id: 1)
    SetEventParameter(ev, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID), MemoryLayout<EventHotKeyID>.size, &id)
    SendEventToEventTarget(ev, GetEventDispatcherTarget())
    check("press reaches the handler", count == 1, "count \(count)")
    unregister()
    check("unregistered, so the key is free again", register(code, mods) == "ok")
    unregister()
    // Informational only: a process inherits trust from the terminal that launched it, so "true" here does not prove the API needs none.
    // Apple documents RegisterEventHotKey as permission-free; this line is for whoever runs the test from a clean launcher.
    print("INFO AXIsProcessTrusted=\(AXIsProcessTrusted())")
    print("INFO enabled system shortcuts read: \(systemHotkeys().count)")
    exit(fails == 0 ? 0 : 1)
}

_ = installHandler()
let app = NSApplication.shared
app.setActivationPolicy(.prohibited)   // no Dock icon, no menu bar, never takes focus

DispatchQueue.global().async {
    while let line = readLine() {
        let w = line.split(separator: " ").map(String.init)
        DispatchQueue.main.async {
            switch w.first ?? "" {
            case "reg":
                if w.count == 3, let c = UInt32(w[1]), let m = UInt32(w[2]) { say(register(c, m)) } else { say("err 0") }
            case "unreg": unregister(); say("ok")
            case "syshot": systemHotkeys().forEach(say); say("end")
            case "quit": exit(0)
            default: break
            }
        }
    }
    exit(0)   // stdin closed: the app is gone, so this helper goes too
}
app.run()
