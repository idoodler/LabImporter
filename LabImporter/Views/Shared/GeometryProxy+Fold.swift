import SwiftUI

extension GeometryProxy {
    /// The display's vertical division (an unfolded iPhone Duo's hinge) in this
    /// view's coordinates, whether or not the device is currently bent — a card
    /// laid across the flat hinge would be split as soon as it is. `nil` before
    /// iOS 27.1, on single-panel devices, and for a horizontal fold.
    var verticalFold: ClosedRange<CGFloat>? {
        guard #available(iOS 27.1, *) else { return nil }
        return reservedRegions(kind: .division, options: .includeInactive)
            .map(\.frame)
            .first { $0.height > $0.width }
            .map { $0.minX...$0.maxX }
    }
}
