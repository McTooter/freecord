import BitChord
import SwiftUI
import UIKit

/// The whole application, in one window: the Compose tree the shared code
/// draws, hosted the way Compose Multiplatform hosts it — the Kotlin entry
/// point builds the view controller and this puts it under SwiftUI's window.
///
/// Everything past this file is Kotlin (:ios for the shell wiring, :sharedUi
/// for what it draws); the framework it comes from is built and embedded by
/// the project's "Embed the Kotlin framework" phase, which asks Gradle for
/// whichever target this build asked Xcode for.
@main
struct BitChordApp: App {
    init() {
        // The earliest Swift that runs: a record ending here dates the death
        // to SwiftUI's own boot, and no record at all to dyld or the Kotlin
        // framework's initializer — both before any of this file's code.
        CrashTrail.append("stage swift-app-init")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

private struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        CrashTrail.append("stage swift-make-vc")
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// The Swift half of the launch journal the Kotlin side keeps (see
/// CrashJournal in :ios): the same append-only file in the same container,
/// one line per stage, so the record spans the process from its first
/// Swift instruction to the first drawn frame — through whatever kills it
/// in between.
enum CrashTrail {
    private static var file: URL? {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        return base.appendingPathComponent("launch-journal.txt")
    }

    static func append(_ line: String) {
        guard let url = file else { return }
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        defer { try? handle.close() }
        _ = try? handle.seekToEnd()
        try? handle.write(contentsOf: Data((line + "\n").utf8))
    }
}
