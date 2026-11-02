import SwiftUI

@main
struct Watch2iPhoneHealthApp: App {
    @StateObject private var health = HealthManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(health)
        }
    }
}
