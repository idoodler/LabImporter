import SwiftUI

/// Sections shown in the iPad sidebar (`HomeView.splitRoot`). On compact
/// widths (iPhone) these are reached through the dashboard's own toolbar
/// instead, so the sidebar is only built when the layout is regular-width.
enum SidebarSection: String, CaseIterable, Identifiable {
    case dashboard, reports, settings
    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .dashboard: return "Lab Results"
        case .reports: return "Reports"
        case .settings: return "Settings"
        }
    }

    var icon: String {
        switch self {
        case .dashboard: return "square.grid.2x2"
        case .reports: return "doc.text"
        case .settings: return "gearshape"
        }
    }
}
