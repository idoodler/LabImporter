import SwiftUI

extension TrendsView {
    /// Selectable time window; a finite case fixes the visible x-span, `.all` fits everything.
    enum TrendWindow: String, CaseIterable, Identifiable {
        case month3, month6, year1, all
        var id: Self { self }

        /// Visible span in days, or `nil` for "fit everything".
        var days: Int? {
            switch self {
            case .month3: return 92
            case .month6: return 183
            case .year1: return 366
            case .all: return nil
            }
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
