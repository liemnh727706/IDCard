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
    var body: some View {
        TabView {
            EmvTabView().tabItem { Label("Thẻ ngân hàng", systemImage: "creditcard") }
            CccdTabView().tabItem { Label("CCCD", systemImage: "person.text.rectangle") }
        }
    }
}
