// Touch ID for the app lock: a one-shot helper. Trawl starts it, reads ONE line from stdout and is done.
//
//   touchid check            -> "available:biometry" (Touch ID can be used), "available:password" (no Touch ID, but the Mac's
//                               login password can be), or "unavailable:<code>". NEVER shows a prompt: it only asks the system
//                               whether it could.
//   touchid auth "<reason>"  -> shows the system's own sheet with <reason>, then prints "ok", "denied", "cancelled" or "error:<code>".
//
// The policy is .deviceOwnerAuthentication: Touch ID first, the Mac's login password as the sheet's own fallback. The secret
// never reaches Trawl; LocalAuthentication only says yes or no. Nothing is stored or sent.
import Foundation
import LocalAuthentication

setvbuf(stdout, nil, _IOLBF, 0)
let args = CommandLine.arguments
let mode = args.count > 1 ? args[1] : ""
let context = LAContext()
var error: NSError?

switch mode {
case "check":
    if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) {
        print("available:biometry")
    } else if context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) {
        print("available:password")
    } else {
        print("unavailable:\(error?.code ?? 0)")
    }
case "auth":
    let reason = args.count > 2 ? args[2] : "unlock Trawl"
    guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
        print("error:\(error?.code ?? 0)")
        exit(0)
    }
    let done = DispatchSemaphore(value: 0)
    context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { ok, err in
        if ok {
            print("ok")
        } else if let e = err as? LAError {
            switch e.code {
            case .userCancel, .systemCancel, .appCancel: print("cancelled")
            case .authenticationFailed: print("denied")
            default: print("error:\(e.code.rawValue)")
            }
        } else {
            print("error:0")
        }
        done.signal()
    }
    done.wait()
default:
    print("error:usage")
}
