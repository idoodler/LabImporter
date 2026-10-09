import SwiftUI

// MARK: - Onboarding flow

extension HomeView {
    /// Four mandatory steps: welcome → disclaimer → Apple Health → the
    /// combined Extras opt-in (iCloud sync, Spotlight, Siri & Shortcuts).
    /// Each gate is mandatory, so the same `fullScreenCover` stays up
    /// (swapping its inner view, cross-fading via `transitionOnboarding`)
    /// until the user clears them all. Every step but the first passes
    /// `onBack` so a mis-tap doesn't require a reinstall to correct; each
    /// step's own `OnboardingScaffold` renders the back button + step
    /// indicator from that and the hard-coded `step`/`totalSteps` it passes.
    @ViewBuilder
    var onboardingFlow: some View {
        Group {
            if !hasSeenWelcome {
                WelcomeView {
                    transitionOnboarding { hasSeenWelcome = true }
                }
            } else if !hasAcknowledgedDisclaimer {
                DisclaimerView(
                    onAcknowledge: { transitionOnboarding { hasAcknowledgedDisclaimer = true } },
                    onBack: { transitionOnboarding { hasSeenWelcome = false } }
                )
            } else if !hasGrantedHealthAccess {
                HealthPermissionView(
                    onGranted: { transitionOnboarding { hasGrantedHealthAccess = true } },
                    onBack: { transitionOnboarding { hasAcknowledgedDisclaimer = false } }
                )
            } else if !hasChosenExtras {
                OnboardingExtrasView(
                    onContinue: { iCloud, showLatestValue, siri in
                        iCloudSyncEnabled = iCloud
                        showLatestValueInSearch = showLatestValue
                        siriPrefs.isEnabled = siri
                        transitionOnboarding {
                            hasChosenICloudSync = true
                            hasChosenSpotlightSearch = true
                            hasChosenSiriIntelligence = true
                        }
                    },
                    onBack: { transitionOnboarding { hasGrantedHealthAccess = false } }
                )
            }
        }
        .transition(.opacity)
    }

    /// True once every onboarding gate is cleared, whichever step was last.
    var onboardingComplete: Bool {
        hasSeenWelcome && hasAcknowledgedDisclaimer && hasGrantedHealthAccess && hasChosenExtras
    }

    /// The combined Extras step (iCloud sync, Spotlight, Siri) is gated by
    /// all three of its underlying preferences at once — there's no separate
    /// `hasChosenExtras` flag, so an install that already finished the old
    /// six-step flow (where these were set one at a time) never sees the new
    /// combined screen again.
    private var hasChosenExtras: Bool {
        hasChosenICloudSync && hasChosenSpotlightSearch && hasChosenSiriIntelligence
    }

    /// Advances or retreats an onboarding gate, animating the cross-fade to
    /// the adjacent step unless Reduce Motion is on.
    private func transitionOnboarding(_ update: () -> Void) {
        guard !reduceMotion else { update(); return }
        withAnimation(.smooth(duration: 0.35), update)
    }
}
