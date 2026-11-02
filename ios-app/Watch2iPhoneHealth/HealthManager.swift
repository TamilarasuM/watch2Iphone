import Foundation
import HealthKit

/// Phase 3 feasibility check: can an app signed with a free Apple ID (installed with a sideloading tool)
/// use HealthKit at all? Every step is written to `lines` so the result is visible on screen.
@MainActor
final class HealthManager: ObservableObject {
    @Published private(set) var lines: [String] = []

    private let store = HKHealthStore()

    /// What the real app will write: steps, heart rate and workouts.
    private var shareTypes: Set<HKSampleType> {
        [
            HKQuantityType(.stepCount),
            HKQuantityType(.heartRate),
            HKWorkoutType.workoutType(),
        ]
    }

    func requestAuthorization() async {
        guard HKHealthStore.isHealthDataAvailable() else {
            append("HealthKit is NOT available on this device")
            return
        }
        append("HealthKit is available; asking for permission…")
        do {
            try await store.requestAuthorization(toShare: shareTypes, read: [])
            append("authorization request finished (the sheet was shown or already answered)")
        } catch {
            append("authorization FAILED: \(error.localizedDescription)")
        }
        for type in shareTypes.sorted(by: { $0.identifier < $1.identifier }) {
            append("  \(type.identifier): \(describe(store.authorizationStatus(for: type)))")
        }
    }

    /// Writes one real step to Apple Health (delete it in Health > Browse > Activity > Steps > Show All Data).
    func writeTestStep() async {
        let now = Date()
        let sample = HKQuantitySample(
            type: HKQuantityType(.stepCount),
            quantity: HKQuantity(unit: .count(), doubleValue: 1),
            start: now.addingTimeInterval(-60),
            end: now
        )
        do {
            try await store.save(sample)
            append("saved 1 test step to Apple Health")
        } catch {
            append("save FAILED: \(error.localizedDescription)")
        }
    }

    private func describe(_ status: HKAuthorizationStatus) -> String {
        switch status {
        case .notDetermined: return "not determined"
        case .sharingDenied: return "denied"
        case .sharingAuthorized: return "authorized"
        @unknown default: return "unknown"
        }
    }

    private func append(_ message: String) {
        lines.append(message)
    }
}
