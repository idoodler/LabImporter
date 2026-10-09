import SwiftUI

// MARK: - Onboarding flow

extension HomeView {
    /// Six-step onboarding: welcome → Apple Health → iCloud sync → Spotlight
    /// search → Siri & Shortcuts → disclaimer. Each gate is mandatory, so the
    /// same fullScreenCover stays up (swapping its inner view) until the user
    /// clears them all.
    @ViewBuilder
    var onboardingFlow: some View {
        if !hasSeenWelcome {
            WelcomeView {
                advanceOnboarding { hasSeenWelcome = true }
            }
            .transition(.opacity)
        } else if !hasGrantedHealthAccess {
            HealthPermissionView {
                advanceOnboarding { hasGrantedHealthAccess = true }
            }
            .transition(.opacity)
        } else if !hasChosenICloudSync {
            CloudSyncOptInView { enabled in
                iCloudSyncEnabled = enabled
                advanceOnboarding { hasChosenICloudSync = true }
            }
            .transition(.opacity)
        } else if !hasChosenSpotlightSearch {
            SpotlightOptInView { showValues in
                showLatestValueInSearch = showValues
                advanceOnboarding { hasChosenSpotlightSearch = true }
            }
            .transition(.opacity)
        } else if !hasChosenSiriIntelligence {
            SiriIntelligenceOptInView { enabled, allowAllValues, allowScanShortcut, allowOnScreenAwareness, allowKnowledgeIndexing, allowInAppSearch in
                siriPrefs.isEnabled = enabled
                siriPrefs.allowAllValues = allowAllValues
                siriPrefs.allowScanShortcut = allowScanShortcut
                siriPrefs.allowOnScreenAwareness = allowOnScreenAwareness
                siriPrefs.allowKnowledgeIndexing = allowKnowledgeIndexing
                siriPrefs.allowInAppSearch = allowInAppSearch
                advanceOnboarding { hasChosenSiriIntelligence = true }
            }
            .transition(.opacity)
        } else {
            DisclaimerView {
                advanceOnboarding { hasAcknowledgedDisclaimer = true }
            }
            .transition(.opacity)
        }
    }

    /// True once every onboarding gate is cleared, whichever step was last.
    var onboardingComplete: Bool {
        hasSeenWelcome && hasAcknowledgedDisclaimer && hasGrantedHealthAccess && hasChosenICloudSync
            && hasChosenSpotlightSearch && hasChosenSiriIntelligence
    }

    /// Advances an onboarding gate, animating the cross-fade to the next step
    /// unless Reduce Motion is on.
    private func advanceOnboarding(_ update: () -> Void) {
        guard !reduceMotion else { update(); return }
        withAnimation(.smooth(duration: 0.35), update)
    }
}
