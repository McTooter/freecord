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
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

private struct ContentView: UIViewRepresentable {
    func makeUIView(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIView(_ uiView: UIViewController, context: Context) {}
}
