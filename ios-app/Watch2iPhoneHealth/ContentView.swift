import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var health: HealthManager

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("Checks whether this sideloaded app may use Apple Health. Step 2 writes one real step, which you can delete in the Health app.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                Button("1. Ask for Health access") {
                    Task { await health.requestAuthorization() }
                }
                .buttonStyle(.borderedProminent)

                Button("2. Write 1 test step") {
                    Task { await health.writeTestStep() }
                }
                .buttonStyle(.bordered)

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 6) {
                        ForEach(Array(health.lines.enumerated()), id: \.offset) { _, line in
                            Text(line)
                                .font(.footnote.monospaced())
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
            }
            .padding()
            .navigationTitle("Watch2iPhone")
        }
    }
}
