import BoomDesign
import SwiftUI

@main
struct BoomBoomApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .preferredColorScheme(.dark)
                .tint(BoomColor.accent)
        }
    }
}
