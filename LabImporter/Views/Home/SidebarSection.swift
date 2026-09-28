import SwiftUI

/// Sections shown in the sidebar (`HomeView.splitRoot`, regular×regular: a
/// full-screen iPad or an unfolded iPhone Duo). In the compact stack the same
/// value drives the pushed Reports list and the Settings sheet, reached
/// through the dashboard's own toolbar, so switching layouts keeps the section.
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
