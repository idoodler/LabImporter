import LabImporterCore
import SwiftUI

extension TrendsView {
    /// Selectable time window: which readings the chart shows (shared rule, as on Android).
    enum TrendWindow: String, CaseIterable, Identifiable {
        case month3, month6, year1, all
        var id: Self { self }

        /// What a trend opens on until the user picks a window (shared rule, as on Android).
        static var opening: Self {
            allCases.first { $0.shared == LabImporterCore.Dashboard.shared.openingTrendWindow } ?? .all
        }

        var shared: LabImporterCore.TrendWindow {
            switch self {
            case .month3: return .months3
            case .month6: return .months6
            case .year1: return .year1
            case .all: return .all
            }
        }

        /// The span in days, or `nil` for every reading.
        var days: Int? {
            shared.days.map { Int(truncating: $0) }
        }

        var label: LocalizedStringKey {
            switch self {
            case .month3: return "3M"
            case .month6: return "6M"
            case .year1: return "1Y"
            case .all: return "All"
            }
        }
    }
}
