import SwiftUI

@main
struct EmvCardReaderApp: App {
    var body: some Scene {
        WindowGroup {
            RootTabView()
        }
    }
}

struct RootTabView: View {
    @StateObject private var appState = AppState()

    var body: some View {
        TabView {
            EmvTabView().tabItem { Label("Thẻ ngân hàng", systemImage: "creditcard") }
            CccdTabView().tabItem { Label("CCCD", systemImage: "person.text.rectangle") }
            FaceIdTabView().tabItem { Label("FaceID", systemImage: "faceid") }
            CombinedTabView().tabItem { Label("Kết hợp", systemImage: "doc.text.below.ecg") }
        }
        .environmentObject(appState)
    }
}
