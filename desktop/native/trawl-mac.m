// Trawl's macOS native AppKit + Carbon bridge (libtrawl_mac.dylib):
//   1. In-process Carbon RegisterEventHotKey on the AppKit main thread (no Accessibility permission;
//      dispatches callback off the Carbon event stack and uses bounded dispatch_async+semaphore with
//      zero dispatch_sync(main_queue), so AWT-EDT <-> AppKit CAccessibility AB-BA deadlocks cannot occur).
//   2. Spotlight-true Quick Panel configuration & raise:
//      - Explicit collectionBehavior = 0x149 (CanJoinAllSpaces | FullScreenAuxiliary | Transient | IgnoresCycle),
//        clearing OpenJDK's FullScreenPrimary (0x80) and MoveToActiveSpace (0x2).
//      - setFloatingPanel:YES / setBecomesKeyOnlyIfNeeded:NO / setWorksWhenModal:YES BEFORE setLevel:101
//        (setFloatingPanel:YES resets level to 3 if called after setLevel:).
//      - SetFrontProcessWithOptions(&psn, kSetFrontProcessFrontWindowOnly) ALONE without whole-app
//        activateIgnoringOtherApps:, so macOS never triggers workspaces-auto-swoosh back to Space 1.
//      - Optional NSApplicationActivationPolicyAccessory when Main Window is hidden to the menu bar.
//      - Main-thread NSWindow.sharingType updates for SecureWindow screenshot privacy.
//   3. Native Cocoa NSDraggingSession carrying standardized NSURL (public.file-url + sandbox extension
//      tokens) with authentic live hardware NSEvent capture via addLocalMonitorForEventsMatchingMask,
//      pressedMouseButtons guard, real 1x1 NSBitmapImageRep secondary items, and Copy|Generic|Link
//      operation mask for WhatsApp Desktop (Mac Catalyst), Chrome, Safari, Slack, Mail, and Finder.
//   4. Native NSPasteboard writeObjects:urls for Cmd+C -> Cmd+V into sandboxed apps like WhatsApp & Safari.
// Nothing here touches the network.

#import <Cocoa/Cocoa.h>
#import <Carbon/Carbon.h>
#include <os/lock.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

typedef void (*TrawlHotkeyCallback)(void);
typedef void (*TrawlDragEndCallback)(int accepted);
typedef void (*TrawlOutsideClickCallback)(void);

static const OSType kTrawlHotKeySig = 0x54524C57; // 'TRLW'
static EventHandlerRef gHotKeyHandlerRef = NULL;
static EventHotKeyRef gHotKeyRef = NULL;
static TrawlHotkeyCallback gHotKeyCallback = NULL;
static _Atomic uint64_t gHotKeyGen = 0;

static id gMouseMonitor = nil;
static id gOutsideClickMonitor = nil;
static TrawlOutsideClickCallback gOutsideClickCallback = NULL;
static NSMutableSet *gActiveDragSources = nil;
static NSEvent *gLastLeftMouseEvent = nil;
static NSTimeInterval gLastLeftMouseTime = 0.0;
static NSInteger gLastEventNumber = 0;

// Lock-free atomic state + tiny diagnostic snapshot updated strictly on AppKit main queue
// (never uses dispatch_sync so AWT-EDT <-> AppKit AB-BA deadlocks remain structurally impossible).
static _Atomic int gOverlayIsKey = 0;
static _Atomic int gOverlayAppActive = 0;
static _Atomic uint64_t gOverlayRaiseGen = 0;
static os_unfair_lock gOverlayDiagLock = OS_UNFAIR_LOCK_INIT;
static char gOverlayDiagBuf[512] = "";
static __weak NSWindow *gObservedOverlayWin = nil;
static id gBecomeKeyObserver = nil;
static id gResignKeyObserver = nil;

// ARC-managed completion state for bounded off-main hotkey registration so a semaphore timeout
// can never leave a dangling stack pointer for the queued main-queue block.
@interface TrawlHotkeyRegState : NSObject
@property (nonatomic, strong) dispatch_semaphore_t sem;
@property (nonatomic, assign) int result;
@property (nonatomic, assign) BOOL cancelled;
@end

@implementation TrawlHotkeyRegState
@end

static void ensureMouseMonitorOnMain(void) {
    if (gMouseMonitor != nil) return;
    gMouseMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:(NSEventMaskLeftMouseDown | NSEventMaskLeftMouseDragged)
                                                          handler:^NSEvent *(NSEvent *event) {
        gLastLeftMouseEvent = event;
        gLastLeftMouseTime = [NSProcessInfo processInfo].systemUptime;
        if (event.eventNumber != 0) {
            gLastEventNumber = event.eventNumber;
        }
        return event;
    }];
}

static void dispatchDragEndAsync(TrawlDragEndCallback cb, int accepted) {
    if (cb == NULL) return;
    // Dispatch off the AppKit main thread so a JNA callback into the JVM can never block AppKit
    // or participate in an AppKit <-> AWT-EventQueue deadlock.
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^{
        cb(accepted);
    });
}

static OSStatus TrawlHotKeyHandler(EventHandlerCallRef nextHandler, EventRef theEvent, void *userData) {
    (void)nextHandler;
    (void)userData;
    EventHotKeyID hkId;
    memset(&hkId, 0, sizeof(hkId));
    OSStatus st = GetEventParameter(
        theEvent,
        kEventParamDirectObject,
        typeEventHotKeyID,
        NULL,
        sizeof(hkId),
        NULL,
        &hkId
    );
    if (st == noErr && hkId.signature == kTrawlHotKeySig && gHotKeyCallback != NULL) {
        TrawlHotkeyCallback cb = gHotKeyCallback;
        // Dispatch off the Carbon EventHandler stack to avoid AppKit-main-thread / AWT-EDT deadlocks.
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^{
            cb();
        });
    }
    return noErr;
}

static int registerHotkeyOnMain(uint32_t keyCode, uint32_t carbonMods, TrawlHotkeyCallback cb, uint64_t myGen) {
    if (atomic_load(&gHotKeyGen) != myGen) {
        return -9879; // Superseded by a newer register/unregister call.
    }
    ensureMouseMonitorOnMain();
    gHotKeyCallback = cb;
    if (gHotKeyHandlerRef == NULL) {
        EventTypeSpec spec = { kEventClassKeyboard, kEventHotKeyPressed };
        OSStatus ist = InstallEventHandler(
            GetApplicationEventTarget(),
            TrawlHotKeyHandler,
            1,
            &spec,
            NULL,
            &gHotKeyHandlerRef
        );
        if (ist != noErr) {
            return (int)ist;
        }
    }
    if (gHotKeyRef != NULL) {
        UnregisterEventHotKey(gHotKeyRef);
        gHotKeyRef = NULL;
    }
    EventHotKeyID hkId = { kTrawlHotKeySig, 1 };
    OSStatus rst = RegisterEventHotKey(
        keyCode,
        carbonMods,
        hkId,
        GetApplicationEventTarget(),
        0,
        &gHotKeyRef
    );
    if (rst == noErr) return 0;
    if (rst == eventHotKeyExistsErr) return 1;
    return (int)rst;
}

int trawl_mac_is_secure_input_enabled(void) {
    return IsSecureEventInputEnabled() ? 1 : 0;
}

void trawl_mac_install_drag_monitor(void) {
    @autoreleasepool {
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                ensureMouseMonitorOnMain();
            }
        });
    }
}

void trawl_mac_set_accessory_policy(int accessory) {
    @autoreleasepool {
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                NSApplicationActivationPolicy want = accessory
                    ? NSApplicationActivationPolicyAccessory
                    : NSApplicationActivationPolicyRegular;
                if ([NSApp activationPolicy] != want) {
                    [NSApp setActivationPolicy:want];
                }
            }
        });
    }
}

// Registers one system-wide Carbon hotkey in-process without dispatch_sync(main_queue).
// Returns: 0 = ok, 1 = taken (eventHotKeyExistsErr), negative = OSStatus error (-9879 on timeout/superseded).
int trawl_mac_hotkey_register(uint32_t keyCode, uint32_t carbonMods, TrawlHotkeyCallback cb) {
    @autoreleasepool {
        uint64_t myGen = atomic_fetch_add(&gHotKeyGen, 1) + 1;
        if ([NSThread isMainThread]) {
            return registerHotkeyOnMain(keyCode, carbonMods, cb, myGen);
        }
        TrawlHotkeyRegState *state = [[TrawlHotkeyRegState alloc] init];
        state.sem = dispatch_semaphore_create(0);
        state.result = -9879;
        state.cancelled = NO;

        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                if (!state.cancelled && atomic_load(&gHotKeyGen) == myGen) {
                    state.result = registerHotkeyOnMain(keyCode, carbonMods, cb, myGen);
                }
                dispatch_semaphore_signal(state.sem);
            }
        });

        long waited = dispatch_semaphore_wait(state.sem, dispatch_time(DISPATCH_TIME_NOW, 3LL * NSEC_PER_SEC));
        if (waited != 0) {
            state.cancelled = YES;
            return -9879;
        }
        return state.result;
    }
}

void trawl_mac_hotkey_unregister(void) {
    @autoreleasepool {
        uint64_t myGen = atomic_fetch_add(&gHotKeyGen, 1) + 1;
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                if (atomic_load(&gHotKeyGen) != myGen) return;
                if (gHotKeyRef != NULL) {
                    UnregisterEventHotKey(gHotKeyRef);
                    gHotKeyRef = NULL;
                }
                gHotKeyCallback = NULL;
            }
        });
    }
}

// Writes enabled macOS symbolic hotkeys as "<code> <carbonMods>\n" lines into buf.
int trawl_mac_system_hotkeys(char *buf, int bufSize) {
    if (!buf || bufSize <= 1) return 0;
    @autoreleasepool {
        NSDictionary *all = [[[NSUserDefaults alloc] initWithSuiteName:@"com.apple.symbolichotkeys"]
                             dictionaryForKey:@"AppleSymbolicHotKeys"];
        if (!all) {
            buf[0] = '\0';
            return 0;
        }
        NSMutableString *out = [NSMutableString string];
        for (id key in all) {
            NSDictionary *d = all[key];
            if (![d isKindOfClass:[NSDictionary class]]) continue;
            NSNumber *enabled = d[@"enabled"];
            if (![enabled isKindOfClass:[NSNumber class]] || ![enabled boolValue]) continue;
            NSDictionary *value = d[@"value"];
            if (![value isKindOfClass:[NSDictionary class]]) continue;
            NSArray *params = value[@"parameters"];
            if (![params isKindOfClass:[NSArray class]] || params.count < 3) continue;
            NSNumber *codeNum = params[1];
            NSNumber *flagsNum = params[2];
            if (![codeNum isKindOfClass:[NSNumber class]] || ![flagsNum isKindOfClass:[NSNumber class]]) continue;
            int code = [codeNum intValue];
            int flags = [flagsNum intValue];
            if (code < 0 || code >= 128) continue;
            int m = 0;
            if (flags & (1 << 20)) m |= cmdKey;
            if (flags & (1 << 17)) m |= shiftKey;
            if (flags & (1 << 19)) m |= optionKey;
            if (flags & (1 << 18)) m |= controlKey;
            [out appendFormat:@"%d %d\n", code, m];
        }
        const char *utf8 = [out UTF8String];
        if (!utf8) {
            buf[0] = '\0';
            return 0;
        }
        size_t len = strlen(utf8);
        if (len >= (size_t)bufSize) len = (size_t)(bufSize - 1);
        memcpy(buf, utf8, len);
        buf[len] = '\0';
        return (int)len;
    }
}

static void removeOutsideClickMonitorOnMain(void) {
    if (gOutsideClickMonitor != nil) {
        [NSEvent removeMonitor:gOutsideClickMonitor];
        gOutsideClickMonitor = nil;
    }
}

void trawl_mac_set_outside_click_callback(TrawlOutsideClickCallback cb) {
    @autoreleasepool {
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                gOutsideClickCallback = cb;
                if (cb == NULL) {
                    removeOutsideClickMonitorOnMain();
                }
            }
        });
    }
}

static void applyOverlayWindowPropsOnMain(NSWindow *win) {
    if (!win) return;
    // Set NSWindowStyleMaskNonactivatingPanel (1 << 7) on NSPanel (OpenJDK AWTWindow_Panel) so
    // Quick Search becomes key and accepts keyboard input WITHOUT activating NSApp — eliminating
    // macOS Spaces auto-swoosh back to Space 1 even while Trawl's Main Window is open on Space 1.
    if ([win isKindOfClass:[NSPanel class]]) {
        NSWindowStyleMask curMask = [win styleMask];
        NSWindowStyleMask wantMask = curMask | NSWindowStyleMaskNonactivatingPanel;
        if (curMask != wantMask) {
            [win setStyleMask:wantMask];
        }
    }
    // Explicit 0x149 mask clears OpenJDK's FullScreenPrimary (0x80) and MoveToActiveSpace (0x2).
    NSWindowCollectionBehavior behavior =
        NSWindowCollectionBehaviorCanJoinAllSpaces |
        NSWindowCollectionBehaviorFullScreenAuxiliary |
        NSWindowCollectionBehaviorTransient |
        NSWindowCollectionBehaviorIgnoresCycle;
    [win setCollectionBehavior:behavior];
    [win setHidesOnDeactivate:NO];
    if ([win isKindOfClass:[NSPanel class]]) {
        NSPanel *panel = (NSPanel *)win;
        // MUST run BEFORE setLevel: because setFloatingPanel:YES resets level down to NSFloatingWindowLevel (3).
        [panel setFloatingPanel:YES];
        [panel setBecomesKeyOnlyIfNeeded:NO];
        [panel setWorksWhenModal:YES];
    }
    [win setLevel:NSPopUpMenuWindowLevel];
}

static void ensureMainWindowControlsOnMain(NSWindow *win) {
    if (!win) return;
    NSWindowStyleMask cur = [win styleMask];
    BOOL fullScreen = (cur & NSWindowStyleMaskFullScreen) != 0;
    NSWindowStyleMask want = cur | NSWindowStyleMaskTitled | NSWindowStyleMaskClosable |
                             NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable;
    if (cur != want) {
        [win setStyleMask:want];
    }
    NSButton *minBtn = [win standardWindowButton:NSWindowMiniaturizeButton];
    if (minBtn) {
        [minBtn setHidden:NO];
        if (!fullScreen) {
            [minBtn setEnabled:YES];
        }
    }
}

void trawl_mac_configure_main(void *nsWindowPtr) {
    if (!nsWindowPtr) return;
    @autoreleasepool {
        NSWindow *win = (__bridge NSWindow *)nsWindowPtr;
        if (!win) return;
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                ensureMainWindowControlsOnMain(win);
            }
        });
    }
}

// Sets NSWindow.sharingType safely on the AppKit main queue (used by SecureWindow.kt for screenshot protection).
void trawl_mac_set_sharing_type(void *nsWindowPtr, int allowCapture) {
    if (!nsWindowPtr) return;
    @autoreleasepool {
        NSWindow *win = (__bridge NSWindow *)nsWindowPtr;
        if (!win) return;
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                [win setSharingType:(allowCapture ? NSWindowSharingReadOnly : NSWindowSharingNone)];
            }
        });
    }
}

// Configures an AWT NSWindow/NSPanel asynchronously on the AppKit main queue.
// CRITICAL: MUST be dispatch_async (NEVER dispatch_sync) because this is called from AWT-EventQueue-0,
// while AppKit Thread can simultaneously block inside CAccessibility -> LWCToolkit.invokeAndWait waiting
// for AWT-EventQueue-0 (dispatch_sync causes an immediate AB-BA deadlock).
void trawl_mac_configure_overlay(void *nsWindowPtr) {
    if (!nsWindowPtr) return;
    @autoreleasepool {
        // Retain NSWindow via ARC before dispatch_async so closing the window cannot dangle nsWindowPtr.
        NSWindow *win = (__bridge NSWindow *)nsWindowPtr;
        if (!win) return;
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                ensureMouseMonitorOnMain();
                applyOverlayWindowPropsOnMain(win);
            }
        });
    }
}

static NSView *findAwtViewRecursive(NSView *view, Class awtViewCls) {
    if (!view) return nil;
    if (awtViewCls && [view isKindOfClass:awtViewCls]) return view;
    for (NSView *sub in view.subviews) {
        NSView *found = findAwtViewRecursive(sub, awtViewCls);
        if (found) return found;
    }
    return nil;
}

static NSView *findKeyResponderViewOnMain(NSWindow *win) {
    if (!win || !win.contentView) return nil;
    Class awtViewCls = NSClassFromString(@"AWTView");
    NSView *awt = findAwtViewRecursive(win.contentView, awtViewCls);
    return awt ?: win.contentView;
}

static void recordOverlayDiagnosticsOnMain(NSWindow *win, const char *phase) {
    if (!win) return;
    int isKey = [win isKeyWindow] ? 1 : 0;
    int canKey = [win canBecomeKeyWindow] ? 1 : 0;
    int isMain = [win isMainWindow] ? 1 : 0;
    int appActive = [NSApp isActive] ? 1 : 0;
    long policy = (long)[NSApp activationPolicy];
    NSRunningApplication *frontApp = [[NSWorkspace sharedWorkspace] frontmostApplication];
    const char *frontName = frontApp.localizedName ? frontApp.localizedName.UTF8String : "nil";
    const char *firstResp = win.firstResponder ? NSStringFromClass([win.firstResponder class]).UTF8String : "nil";
    const char *contentView = win.contentView ? NSStringFromClass([win.contentView class]).UTF8String : "nil";
    long level = (long)[win level];
    unsigned long mask = (unsigned long)[win styleMask];
    int onSpace = [win isOnActiveSpace] ? 1 : 0;
    int secIn = IsSecureEventInputEnabled() ? 1 : 0;

    atomic_store(&gOverlayIsKey, isKey);
    atomic_store(&gOverlayAppActive, appActive);

    char tmp[512];
    snprintf(
        tmp, sizeof(tmp),
        "p=%s key=%d can=%d main=%d act=%d pol=%ld front=%s fr=%s cv=%s lv=%ld m=0x%lx sp=%d sec=%d",
        phase ? phase : "?", isKey, canKey, isMain, appActive, policy,
        frontName, firstResp, contentView, level, mask, onSpace, secIn
    );
    os_unfair_lock_lock(&gOverlayDiagLock);
    strlcpy(gOverlayDiagBuf, tmp, sizeof(gOverlayDiagBuf));
    os_unfair_lock_unlock(&gOverlayDiagLock);
}

static void observeOverlayKeyEventsOnMain(NSWindow *win) {
    if (!win) return;
    if (gObservedOverlayWin == win && gBecomeKeyObserver != nil) return;
    NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
    if (gBecomeKeyObserver) { [nc removeObserver:gBecomeKeyObserver]; gBecomeKeyObserver = nil; }
    if (gResignKeyObserver) { [nc removeObserver:gResignKeyObserver]; gResignKeyObserver = nil; }
    gObservedOverlayWin = win;
    __weak NSWindow *weakWin = win;
    gBecomeKeyObserver = [nc addObserverForName:NSWindowDidBecomeKeyNotification
                                         object:win
                                          queue:[NSOperationQueue mainQueue]
                                     usingBlock:^(NSNotification *note) {
        (void)note;
        NSWindow *w = weakWin;
        if (w) recordOverlayDiagnosticsOnMain(w, "didBecomeKey");
    }];
    gResignKeyObserver = [nc addObserverForName:NSWindowDidResignKeyNotification
                                         object:win
                                          queue:[NSOperationQueue mainQueue]
                                     usingBlock:^(NSNotification *note) {
        (void)note;
        NSWindow *w = weakWin;
        if (w) recordOverlayDiagnosticsOnMain(w, "didResignKey");
        else atomic_store(&gOverlayIsKey, 0);
    }];
}

int trawl_mac_is_overlay_key(void) {
    return atomic_load(&gOverlayIsKey);
}

int trawl_mac_overlay_diagnostics(char *buf, int bufSize) {
    if (!buf || bufSize <= 1) return 0;
    os_unfair_lock_lock(&gOverlayDiagLock);
    size_t len = strlcpy(buf, gOverlayDiagBuf, (size_t)bufSize);
    os_unfair_lock_unlock(&gOverlayDiagLock);
    if (len >= (size_t)bufSize) len = (size_t)(bufSize - 1);
    return (int)len;
}

static void assertOverlayKeyOnMain(NSWindow *win, BOOL allowActivate) {
    if (!win || ![win isVisible]) return;
    if (allowActivate || [NSApp activationPolicy] == NSApplicationActivationPolicyAccessory) {
        if (@available(macOS 14.0, *)) {
            [NSApp activate];
        }
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        [[NSRunningApplication currentApplication] activateWithOptions:NSApplicationActivateIgnoringOtherApps];
        [NSApp activateIgnoringOtherApps:YES];
#pragma clang diagnostic pop
    }
    [win orderFrontRegardless];
    [win makeKeyAndOrderFront:nil];
    [win makeKeyWindow];
    NSView *targetView = findKeyResponderViewOnMain(win);
    if (targetView) {
        [win makeFirstResponder:targetView];
    }
}

// Raises the Quick Panel overlay above the active app (including WhatsApp and full-screen Spaces)
// and guarantees genuine macOS WindowServer key-window + AWTView firstResponder focus without clicking.
void trawl_mac_raise_overlay(void *nsWindowPtr, int activateApp) {
    if (!nsWindowPtr) return;
    @autoreleasepool {
        NSWindow *win = (__bridge NSWindow *)nsWindowPtr;
        if (!win) return;
        uint64_t myGen = atomic_fetch_add(&gOverlayRaiseGen, 1) + 1;
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                observeOverlayKeyEventsOnMain(win);
                if ([win isKindOfClass:[NSPanel class]]) {
                    // 1. Anchor the NSPanel onto the active Space (including Full-Screen apps) BEFORE
                    //    touching process activation so macOS sees Trawl's key window is ALREADY on this Space.
                    if ([win isVisible] && ![win isOnActiveSpace]) {
                        [win orderOut:nil];
                    }
                    applyOverlayWindowPropsOnMain(win);
                    [win orderFrontRegardless];
                    // 2. Grant front-window-only activation (never ActivateAllWindows) so macOS routes hardware
                    //    keystrokes to this NSPanel without pulling Main Window or switching Spaces.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
                    ProcessSerialNumber psn = { 0, kCurrentProcess };
                    SetFrontProcessWithOptions(&psn, kSetFrontProcessFrontWindowOnly);
#pragma clang diagnostic pop
                    assertOverlayKeyOnMain(win, activateApp != 0);
                    recordOverlayDiagnosticsOnMain(win, "raiseNow");

                    // 3. WindowServer processes SetFrontProcessWithOptions / activate asynchronously (~10-40ms).
                    //    Verify at +30ms and +90ms; if still not WindowServer key window, escalate with
                    //    activateIgnoringOtherApps (never ActivateAllWindows) + makeKeyWindow + AWTView firstResponder.
                    __weak NSWindow *weakWin = win;
                    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 30LL * NSEC_PER_MSEC), dispatch_get_main_queue(), ^{
                        @autoreleasepool {
                            if (atomic_load(&gOverlayRaiseGen) != myGen) return;
                            NSWindow *w = weakWin;
                            if (!w || ![w isVisible]) return;
                            if (![w isKeyWindow] || ![NSApp isActive]) {
                                assertOverlayKeyOnMain(w, YES);
                            } else {
                                NSView *rv = findKeyResponderViewOnMain(w);
                                if (rv && w.firstResponder != rv) [w makeFirstResponder:rv];
                            }
                            recordOverlayDiagnosticsOnMain(w, "post30ms");
                        }
                    });
                    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 90LL * NSEC_PER_MSEC), dispatch_get_main_queue(), ^{
                        @autoreleasepool {
                            if (atomic_load(&gOverlayRaiseGen) != myGen) return;
                            NSWindow *w = weakWin;
                            if (!w || ![w isVisible]) return;
                            if (![w isKeyWindow] || ![NSApp isActive]) {
                                assertOverlayKeyOnMain(w, YES);
                            } else {
                                NSView *rv = findKeyResponderViewOnMain(w);
                                if (rv && w.firstResponder != rv) [w makeFirstResponder:rv];
                            }
                            recordOverlayDiagnosticsOnMain(w, "post90ms");
                        }
                    });

                    removeOutsideClickMonitorOnMain();
                    gOutsideClickMonitor = [NSEvent addGlobalMonitorForEventsMatchingMask:(NSEventMaskLeftMouseDown | NSEventMaskRightMouseDown)
                                                                                  handler:^(NSEvent *event) {
                        (void)event;
                        NSWindow *strongWin = weakWin;
                        if (!strongWin || ![strongWin isVisible]) return;
                        if (gActiveDragSources && gActiveDragSources.count > 0) return;
                        if (NSPointInRect([NSEvent mouseLocation], [strongWin frame])) return;
                        TrawlOutsideClickCallback cb = gOutsideClickCallback;
                        if (cb != NULL) {
                            dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^{
                                cb();
                            });
                        }
                    }];
                    return;
                }
                [win orderFrontRegardless];
                if (activateApp) {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
                    ProcessSerialNumber psn = { 0, kCurrentProcess };
                    SetFrontProcessWithOptions(&psn, kSetFrontProcessFrontWindowOnly);
#pragma clang diagnostic pop
                }
                assertOverlayKeyOnMain(win, activateApp != 0);
                recordOverlayDiagnosticsOnMain(win, "raiseNonPanel");
            }
        });
    }
}

// Orders out the Quick Panel overlay and tears down the outside-click monitor so key focus returns
// cleanly to the underlying app (WhatsApp, Chrome, Full-Screen app) without switching Spaces.
void trawl_mac_hide_overlay(void *nsWindowPtr) {
    if (!nsWindowPtr) return;
    @autoreleasepool {
        NSWindow *win = (__bridge NSWindow *)nsWindowPtr;
        if (!win) return;
        atomic_fetch_add(&gOverlayRaiseGen, 1);
        atomic_store(&gOverlayIsKey, 0);
        atomic_store(&gOverlayAppActive, 0);
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                removeOutsideClickMonitorOnMain();
                [win orderOut:nil];
            }
        });
    }
}

void trawl_mac_raise_main(void *nsWindowPtr) {
    if (!nsWindowPtr) return;
    @autoreleasepool {
        NSWindow *win = (__bridge NSWindow *)nsWindowPtr;
        if (!win) return;
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                if ([NSApp activationPolicy] != NSApplicationActivationPolicyRegular) {
                    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
                }
                ensureMainWindowControlsOnMain(win);
                if ([win isMiniaturized]) {
                    [win deminiaturize:nil];
                }
                if (@available(macOS 14.0, *)) {
                    [NSApp activate];
                }
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
                [NSApp activateIgnoringOtherApps:YES];
                [[NSRunningApplication currentApplication]
                    activateWithOptions:(NSApplicationActivateAllWindows | NSApplicationActivateIgnoringOtherApps)];
#pragma clang diagnostic pop
                [win makeKeyAndOrderFront:nil];
                if (win.contentView) {
                    [win makeFirstResponder:win.contentView];
                }
            }
        });
    }
}

// ---- Native NSPasteboard File Copy (NSURL / public.file-url + sandbox tokens) ----------------

int trawl_mac_copy_files(const char **paths, int count) {
    if (!paths || count <= 0) return 0;
    @autoreleasepool {
        NSMutableArray<NSURL *> *urls = [NSMutableArray arrayWithCapacity:(NSUInteger)count];
        NSFileManager *fm = [NSFileManager defaultManager];
        for (int i = 0; i < count; i++) {
            if (paths[i] == NULL) continue;
            NSString *s = [fm stringWithFileSystemRepresentation:paths[i] length:strlen(paths[i])];
            if (s.length == 0) s = [NSString stringWithUTF8String:paths[i]];
            if (s.length == 0) continue;
            BOOL isDir = NO;
            if (![fm fileExistsAtPath:s isDirectory:&isDir]) continue;
            NSURL *u = [[NSURL fileURLWithPath:s isDirectory:isDir] URLByStandardizingPath];
            if (u) [urls addObject:u];
        }
        if (urls.count == 0) return 0;
        // MUST be dispatch_async (NEVER dispatch_sync) because Cmd+C runs on AWT-EventQueue-0.
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                NSPasteboard *pb = [NSPasteboard generalPasteboard];
                [pb clearContents];
                [pb writeObjects:urls];
            }
        });
        return 1;
    }
}

// ---- Native Cocoa Drag-and-Drop (NSURL / public.file-url) ------------------------------------

static NSImage *makeTransparent1x1Image(void) {
    NSBitmapImageRep *rep = [[NSBitmapImageRep alloc]
        initWithBitmapDataPlanes:NULL
                      pixelsWide:1
                      pixelsHigh:1
                   bitsPerSample:8
                 samplesPerPixel:4
                        hasAlpha:YES
                        isPlanar:NO
                  colorSpaceName:NSCalibratedRGBColorSpace
                     bytesPerRow:4
                    bitsPerPixel:32];
    if (rep && rep.bitmapData) {
        memset(rep.bitmapData, 0, 4);
    }
    NSImage *img = [[NSImage alloc] initWithSize:NSMakeSize(1, 1)];
    if (rep) [img addRepresentation:rep];
    return img;
}

@interface TrawlDragSource : NSObject <NSDraggingSource>
@property (nonatomic, assign) TrawlDragEndCallback onEnd;
@end

@implementation TrawlDragSource

- (NSDragOperation)draggingSession:(NSDraggingSession *)session
    sourceOperationMaskForDraggingContext:(NSDraggingContext)context {
    (void)session;
    if (context == NSDraggingContextWithinApplication) {
        return NSDragOperationNone;
    }
    // Include Generic & Link alongside Copy: WhatsApp Desktop (Mac Catalyst), Safari, and Chrome propose
    // NSDragOperationGeneric (0x4); returning Copy-only (0x1) causes AppKit to reject drops (0x4 & 0x1 == 0).
    // Never include Move or Delete so sandboxed drop targets cannot delete original files.
    return NSDragOperationCopy | NSDragOperationGeneric | NSDragOperationLink;
}

- (BOOL)ignoreModifierKeysForDraggingSession:(NSDraggingSession *)session {
    (void)session;
    return NO;
}

- (void)draggingSession:(NSDraggingSession *)session
           endedAtPoint:(NSPoint)screenPoint
              operation:(NSDragOperation)operation {
    (void)session;
    (void)screenPoint;
    TrawlDragEndCallback cb = self.onEnd;
    self.onEnd = NULL;
    if (gActiveDragSources) {
        [gActiveDragSources removeObject:self];
    }
    dispatchDragEndAsync(cb, operation != NSDragOperationNone ? 1 : 0);
}

@end

// Starts a native Cocoa NSDraggingSession carrying standardized NSURL items (public.file-url +
// auto-synthesized NSFilenamesPboardType + App Sandbox extension tokens) and our Retina PNG ghost.
int trawl_mac_start_file_drag(
    void *nsWindowPtr,
    const char **paths,
    int count,
    const unsigned char *pngBytes,
    int pngLen,
    TrawlDragEndCallback onEnd
) {
    if (!nsWindowPtr || !paths || count <= 0) return 0;
    @autoreleasepool {
        NSWindow *win = (__bridge NSWindow *)nsWindowPtr;
        if (!win) return 0;

        NSFileManager *fm = [NSFileManager defaultManager];
        NSMutableArray<NSString *> *pathStrings = [NSMutableArray arrayWithCapacity:(NSUInteger)count];
        for (int i = 0; i < count; i++) {
            if (paths[i] != NULL) {
                NSString *s = [fm stringWithFileSystemRepresentation:paths[i] length:strlen(paths[i])];
                if (s.length == 0) s = [NSString stringWithUTF8String:paths[i]];
                if (s.length > 0) [pathStrings addObject:s];
            }
        }
        if (pathStrings.count == 0) return 0;

        NSData *pngData = (pngBytes != NULL && pngLen > 0)
            ? [NSData dataWithBytes:pngBytes length:(NSUInteger)pngLen]
            : nil;

        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                ensureMouseMonitorOnMain();
                // Abort cleanly if the user already released the mouse button before main-queue dispatch ran.
                if (([NSEvent pressedMouseButtons] & 1) == 0) {
                    dispatchDragEndAsync(onEnd, 0);
                    return;
                }

                NSView *view = win.contentView;
                if (!view || ![win isVisible]) {
                    dispatchDragEndAsync(onEnd, 0);
                    return;
                }

                NSImage *ghost = nil;
                if (pngData != nil) {
                    ghost = [[NSImage alloc] initWithData:pngData];
                    if (ghost != nil) {
                        // Render at 2x pixels (496x92) into crisp 248x46 pt Retina logical bounds.
                        [ghost setSize:NSMakeSize(248.0, 46.0)];
                    }
                }
                if (ghost == nil) {
                    ghost = [[NSWorkspace sharedWorkspace] iconForFile:pathStrings.firstObject];
                    if (ghost) [ghost setSize:NSMakeSize(48.0, 48.0)];
                }
                NSSize ghostSize = ghost ? ghost.size : NSMakeSize(248.0, 46.0);
                NSPoint screenPt = [NSEvent mouseLocation];
                NSPoint winPt = [win convertPointFromScreen:screenPt];
                NSPoint viewPt = [view convertPoint:winPt fromView:nil];

                NSImage *blankImg = makeTransparent1x1Image();
                NSFileManager *mainFm = [NSFileManager defaultManager];

                NSMutableArray<NSDraggingItem *> *items = [NSMutableArray arrayWithCapacity:pathStrings.count];
                for (NSUInteger i = 0; i < pathStrings.count; i++) {
                    NSString *p = pathStrings[i];
                    BOOL isDir = NO;
                    if (![mainFm fileExistsAtPath:p isDirectory:&isDir]) continue;
                    NSURL *url = [[NSURL fileURLWithPath:p isDirectory:isDir] URLByStandardizingPath];
                    if (!url) continue;
                    NSDraggingItem *item = [[NSDraggingItem alloc] initWithPasteboardWriter:url];
                    if (items.count == 0 && ghost != nil) {
                        NSRect frame = NSMakeRect(
                            viewPt.x - 20.0,
                            viewPt.y - ghostSize.height / 2.0,
                            ghostSize.width,
                            ghostSize.height
                        );
                        [item setDraggingFrame:frame contents:ghost];
                    } else {
                        NSRect frame = NSMakeRect(viewPt.x, viewPt.y, 1.0, 1.0);
                        [item setDraggingFrame:frame contents:blankImg];
                    }
                    [items addObject:item];
                }

                if (items.count == 0) {
                    dispatchDragEndAsync(onEnd, 0);
                    return;
                }

                // Prefer the authentic live hardware NSEvent captured by our local monitor; fall back gracefully.
                NSEvent *ev = nil;
                NSTimeInterval now = [NSProcessInfo processInfo].systemUptime;
                if (gLastLeftMouseEvent != nil &&
                    gLastLeftMouseEvent.windowNumber == win.windowNumber &&
                    (now - gLastLeftMouseTime) < 2.0) {
                    ev = gLastLeftMouseEvent;
                }
                if (!ev) {
                    NSEvent *cur = [NSApp currentEvent];
                    if (cur != nil &&
                        (cur.type == NSEventTypeLeftMouseDown || cur.type == NSEventTypeLeftMouseDragged) &&
                        cur.windowNumber == win.windowNumber) {
                        ev = cur;
                    }
                }
                if (!ev) {
                    ev = [NSEvent mouseEventWithType:NSEventTypeLeftMouseDragged
                                            location:winPt
                                       modifierFlags:[NSEvent modifierFlags]
                                           timestamp:now
                                        windowNumber:win.windowNumber
                                             context:nil
                                         eventNumber:gLastEventNumber
                                          clickCount:1
                                            pressure:1.0f];
                }

                if (!gActiveDragSources) {
                    gActiveDragSources = [NSMutableSet set];
                }
                TrawlDragSource *source = [[TrawlDragSource alloc] init];
                source.onEnd = onEnd;
                [gActiveDragSources addObject:source];

                NSDraggingSession *session = nil;
                @try {
                    session = [view beginDraggingSessionWithItems:items event:ev source:source];
                } @catch (NSException *ex) {
                    (void)ex;
                    session = nil;
                }
                if (!session) {
                    source.onEnd = NULL;
                    [gActiveDragSources removeObject:source];
                    dispatchDragEndAsync(onEnd, 0);
                    return;
                }
                session.animatesToStartingPositionsOnCancelOrFail = YES;
                session.draggingFormation = NSDraggingFormationNone;
            }
        });
        return 1;
    }
}
