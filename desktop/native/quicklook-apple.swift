// The macOS "full preview" for the quick-search panel: the system's own Quick Look panel (QLPreviewPanel), the same window Finder shows on
// Space, for any file type the Mac can preview. It ships with macOS; this helper only asks for it. Trawl starts it as a child process.
//
//   quicklook-apple --self-check            -> "ok:quicklook" (the QuickLookUI framework loads, the panel class exists and a file URL is a
//                                              valid preview item). Shows NOTHING: no window, no Dock icon, nothing activated. Used by the
//                                              build's check and by tests.
//   quicklook-apple show <index> <path>...   -> opens the Quick Look panel on the given files (the arrow keys step between them) starting at
//                                              <index>, in front of everything, then exits when the panel is closed (Esc, Space or its close
//                                              button) or when Trawl closes this program's input. Prints "shown" when the panel is up and
//                                              "closed" when it goes. Prints "error:<what>" and exits when it cannot show anything.
//
// Files that no longer exist are dropped first. Nothing is read, copied or sent by this program: Quick Look reads the files itself.
// The caller only passes files it may show (never a sensitive file, never a picture the privacy blur is hiding).
import AppKit
import Foundation
import QuickLookUI

setvbuf(stdout, nil, _IOLBF, 0)
let args = Array(CommandLine.arguments.dropFirst())

func say(_ line: String) { print(line); fflush(stdout) }

if args.first == "--self-check" {
    // The class has to resolve at run time, not only at link time, and a file URL has to be accepted as an item; building neither shows anything.
    guard NSClassFromString("QLPreviewPanel") != nil else { say("error:noclass"); exit(0) }
    let tmp = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("trawl-quicklook-selfcheck-\(getpid()).txt")
    do { try "x".write(to: tmp, atomically: true, encoding: .utf8) } catch { say("error:tmp"); exit(0) }
    defer { try? FileManager.default.removeItem(at: tmp) }
    let item: QLPreviewItem = tmp as NSURL
    say(item.previewItemURL == tmp ? "ok:quicklook" : "error:item")
    exit(0)
}

guard args.count >= 3, args[0] == "show", let startIndex = Int(args[1]) else { say("error:usage"); exit(0) }
let requested = Array(args.dropFirst(2))
// Keep the index pointing at the same file after missing ones are dropped.
var urls: [NSURL] = []
var start = 0
for (i, p) in requested.enumerated() where FileManager.default.fileExists(atPath: p) {
    if i == startIndex { start = urls.count }
    urls.append(URL(fileURLWithPath: p) as NSURL)
}
if urls.isEmpty { say("error:nofiles"); exit(0) }
if start >= urls.count { start = 0 }

final class Host: NSObject, NSApplicationDelegate, QLPreviewPanelDataSource, QLPreviewPanelDelegate {
    var finished = false

    func finish() {
        if finished { return }
        finished = true
        say("closed")
        exit(0)
    }

    func numberOfPreviewItems(in panel: QLPreviewPanel!) -> Int { urls.count }
    func previewPanel(_ panel: QLPreviewPanel!, previewItemAt index: Int) -> QLPreviewItem! { urls[index] }

    func applicationDidFinishLaunching(_ n: Notification) {
        guard let panel = QLPreviewPanel.shared() else { say("error:nopanel"); exit(0) }
        panel.dataSource = self
        panel.delegate = self
        panel.reloadData()
        panel.currentPreviewItemIndex = start
        // The panel is a floating window of this program; the program must be the active app for the panel to take the keyboard.
        NSApp.activate(ignoringOtherApps: true)
        panel.makeKeyAndOrderFront(nil)
        say("shown")
        // Closing the panel by any means ends the program.
        NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: panel, queue: .main) { _ in self.finish() }
        // Trawl closing our input (it quit, or it closed the preview itself) ends the program too, so no panel is ever left behind.
        DispatchQueue.global().async {
            while FileHandle.standardInput.availableData.count > 0 { }
            DispatchQueue.main.async { self.finish() }
        }
        // A forgotten panel cannot keep this running for ever.
        DispatchQueue.main.asyncAfter(deadline: .now() + 3600) { self.finish() }
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)   // no Dock icon, no menu bar: only the preview
let host = Host()
app.delegate = host
app.run()
