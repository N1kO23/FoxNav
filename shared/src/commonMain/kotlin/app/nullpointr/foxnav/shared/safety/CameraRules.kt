package app.nullpointr.foxnav.shared.safety

/**
 * Where drivers may be warned of speed cameras. A few countries forbid it, so FoxNav keeps quiet
 * there whatever the settings say:
 * - Germany: StVO §23 (1c) forbids drivers to use devices or apps that warn of speed checks.
 * - Switzerland: SVG Art. 98a forbids warning of traffic checks.
 * - France: apps may only show broad "danger zones", not where cameras are.
 *
 * Unknown countries (no position yet, no map data) are treated as allowed. Laws change: keep this
 * list in step with docs/architecture.md.
 */
object CameraRules {
    private val NO_CAMERA_WARNINGS = setOf("DE", "CH", "FR")

    fun warningsAllowed(countryCode: String?): Boolean = countryCode?.uppercase() !in NO_CAMERA_WARNINGS
}
