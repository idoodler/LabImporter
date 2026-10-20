import LabImporterCore
import SwiftUI

extension TrendsView {
    /// Selectable time window; a finite case fixes the visible x-span, `.all` fits everything.
    enum TrendWindow: String, CaseIterable, Identifiable {
        case month3, month6, year1, all
        var id: Self { self }

        /// Visible span in days, or `nil` for "fit everything" (shared with Android).
        var days: Int? {
            let shared: LabImporterCore.TrendWindow
            switch self {
            case .month3: shared = .months3
            case .month6: shared = .months6
            case .year1: shared = .year1
            case .all: shared = .all
            }
            return shared.days.map { Int(truncating: $0) }
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
