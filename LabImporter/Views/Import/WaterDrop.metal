#include <metal_stdlib>
#include <SwiftUI/SwiftUI_Metal.h>
using namespace metal;

/// Refraction lens for the import screen's water drop (`ImportWaterDropView`).
///
/// Applied as a SwiftUI `layerEffect` over the layer containing the category
/// wash and the status text, so everything behind the drop — text included —
/// is optically bent by it. The drop's outline matches `WaterDropShape`:
/// a circle of `radius` deformed by a mode-2 (elliptical) and mode-3
/// (triangular) surface wave, so the shader's lens and the painted overlays
/// (specular, rim glints) agree on where the drop is.
///
/// Inside the outline the layer is sampled toward the center (a thin glass
/// sphere's magnification, easing to zero displacement at the rim), and the
/// red/blue channels are sampled at slightly different magnifications —
/// chromatic aberration that grows toward the rim. A faint brightness lift
/// keeps the glass readable over quiet areas.
[[ stitchable ]] half4 waterDrop(
    float2 position,
    SwiftUI::Layer layer,
    float2 center,
    float radius,
    float2 wobble2,   // (amplitude, angle) of the mode-2 squash/stretch
    float2 wobble3,   // (amplitude, phase) of the mode-3 ripple
    float strength    // center magnification, 0…1
) {
    float2 dir = position - center;
    float dist = length(dir);

    float theta = atan2(dir.y, dir.x);
    float boundary = radius * (1.0
        + wobble2.x * cos(2.0 * (theta - wobble2.y))
        + wobble3.x * cos(3.0 * theta + wobble3.y));

    if (boundary <= 0.0 || dist >= boundary) {
        return layer.sample(position);
    }

    float n = dist / boundary;  // 0 at the center … 1 at the rim
    // Thin glass sphere: magnified center easing back to no offset at the
    // rim, so the image flows continuously across the drop's edge.
    float zoom = 1.0 - strength * (1.0 - n * n);
    // Chromatic aberration grows toward the rim.
    float rim = smoothstep(0.55, 1.0, n);
    float aberration = 0.010 + 0.035 * rim;

    half4 g = layer.sample(center + dir * zoom);
    half3 color;
    color.r = layer.sample(center + dir * (zoom * (1.0 - aberration))).r;
    color.g = g.g;
    color.b = layer.sample(center + dir * (zoom * (1.0 + aberration))).b;

    // Faint frost plus a bright rim so the glass reads even over flat color.
    half lift = half(0.04 + 0.10 * pow(rim, 3.0));
    return half4(mix(color, half3(1.0h), lift), g.a);
}
