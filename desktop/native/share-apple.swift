// The macOS share sheet for "Share" in a result's menu: a one-shot helper. Trawl starts it, reads ONE line from stdout and is done.
//
//   share-apple --self-check          -> "ok:picker" (the share machinery loads and the picker builds its standard Share menu item for a file). Shows NOTHING:
//                                        no window, no sheet, no Dock icon. Used by the build's check and by tests.
//   share-apple share <path>...       -> shows the system's own share sheet (NSSharingServicePicker) for those files, next to the mouse,
//                                        then prints "ok" (a service took the files), "cancelled" (the sheet was dismissed) or
//                                        "error:<code>". Nothing is stored or sent by Trawl: the service the person picks does that.
//
// The files are handed over as file URLs, so Mail, Messages, AirDrop and the rest get the real files. A sensitive file is asked about
// in Trawl's own "take it out of Trawl?" dialog before this helper is ever started.
import AppKit
import Foundation

setvbuf(stdout, nil, _IOLBF, 0)
let args = Array(CommandLine.arguments.dropFirst())

func say(_ line: String) { print(line); fflush(stdout) }

if args.first == "--self-check" {
    let tmp = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("trawl-share-selfcheck-\(getpid()).txt")
    do { try "x".write(to: tmp, atomically: true, encoding: .utf8) } catch { say("error:tmp"); exit(0) }
    defer { try? FileManager.default.removeItem(at: tmp) }
    // The picker itself is asked for its standard Share menu item (the supported way since macOS 13); building it shows nothing.
    let picker = NSSharingServicePicker(items: [tmp])
    let item = picker.standardShareMenuItem
    say(item.title.isEmpty ? "error:noshareitem" : "ok:picker")
    exit(0)
}

guard args.first == "share", args.count > 1 else { say("error:usage"); exit(0) }
let urls = args.dropFirst().map { URL(fileURLWithPath: $0) }.filter { FileManager.default.fileExists(atPath: $0.path) }
if urls.isEmpty { say("error:nofiles"); exit(0) }

/// A borderless window can not become key by itself; the picker wants one to anchor to.
final class AnchorWindow: NSWindow {
    override var canBecomeKey: Bool { true }
}

final class Host: NSObject, NSApplicationDelegate, NSSharingServicePickerDelegate, NSSharingServiceDelegate {
    var window: AnchorWindow!
    var finished = false

    func finish(_ line: String) {
        if finished { return }
        finished = true
        say(line)
        exit(0)
    }

    func applicationDidFinishLaunching(_ n: Notification) {
        let p = NSEvent.mouseLocation
        window = AnchorWindow(contentRect: NSRect(x: p.x, y: p.y, width: 1, height: 1), styleMask: .borderless, backing: .buffered, defer: false)
        window.isOpaque = false
        window.backgroundColor = .clear
        window.hasShadow = false
        window.level = .floating
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        let picker = NSSharingServicePicker(items: urls)
        picker.delegate = self
        let view = window.contentView!
        picker.show(relativeTo: view.bounds, of: view, preferredEdge: .minY)
        // The sheet has no timeout of its own: give up after ten minutes so a forgotten sheet cannot keep this running.
        DispatchQueue.main.asyncAfter(deadline: .now() + 600) { self.finish("cancelled") }
    }

    // The person picked a service, or (nil) dismissed the sheet.
    func sharingServicePicker(_ picker: NSSharingServicePicker, didChoose service: NSSharingService?) {
        if service == nil { finish("cancelled") }
    }

    func sharingServicePicker(_ picker: NSSharingServicePicker, delegateFor sharingService: NSSharingService) -> NSSharingServiceDelegate? { self }

    func sharingService(_ sharingService: NSSharingService, didShareItems items: [Any]) { finish("ok") }

    func sharingService(_ sharingService: NSSharingService, didFailToShareItems items: [Any], error: Error) {
        let e = error as NSError
        if e.domain == NSCocoaErrorDomain && e.code == NSUserCancelledError { finish("cancelled") } else { finish("error:\(e.code)") }
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)   // no Dock icon, no menu bar: only the sheet
let host = Host()
app.delegate = host
app.run()
