package app.nullpointr.foxnav.convoy

import androidx.compose.ui.graphics.vector.ImageVector
import app.nullpointr.foxnav.protocol.convoy.v0.QuickMessage
import app.nullpointr.foxnav.ui.icons.FoxIcons

/** The quick messages offered as buttons, in the mockup's order: stops first, then warnings. */
val QuickMessageKinds = listOf(
    QuickMessage.Kind.KIND_FUEL,
    QuickMessage.Kind.KIND_BREAK,
    QuickMessage.Kind.KIND_PULL_OVER,
    QuickMessage.Kind.KIND_POLICE_AHEAD,
    QuickMessage.Kind.KIND_HAZARD_AHEAD,
    QuickMessage.Kind.KIND_SLOW_DOWN,
    QuickMessage.Kind.KIND_ALL_GOOD,
)

/** Button icon, from the convoy mockup. */
val QuickMessage.Kind.icon: ImageVector
    get() = when (this) {
        QuickMessage.Kind.KIND_FUEL -> FoxIcons.Fuel
        QuickMessage.Kind.KIND_BREAK -> FoxIcons.Coffee
        QuickMessage.Kind.KIND_PULL_OVER -> FoxIcons.PullOver
        QuickMessage.Kind.KIND_POLICE_AHEAD -> FoxIcons.Police
        QuickMessage.Kind.KIND_HAZARD_AHEAD -> FoxIcons.Report
        QuickMessage.Kind.KIND_SLOW_DOWN -> FoxIcons.SlowDown
        QuickMessage.Kind.KIND_ALL_GOOD, QuickMessage.Kind.KIND_UNSPECIFIED -> FoxIcons.AllGood
    }

/** The button's Japanese accent (design/README.md lists them for checking). */
val QuickMessage.Kind.japanese: String?
    get() = when (this) {
        QuickMessage.Kind.KIND_FUEL -> "給油"
        QuickMessage.Kind.KIND_BREAK -> "休憩"
        QuickMessage.Kind.KIND_PULL_OVER -> "停車"
        QuickMessage.Kind.KIND_POLICE_AHEAD -> "警察"
        QuickMessage.Kind.KIND_HAZARD_AHEAD -> "危険"
        QuickMessage.Kind.KIND_SLOW_DOWN -> "減速"
        QuickMessage.Kind.KIND_ALL_GOOD -> "問題なし"
        QuickMessage.Kind.KIND_UNSPECIFIED -> null
    }

/** Button label. */
val QuickMessage.Kind.label: String
    get() = when (this) {
        QuickMessage.Kind.KIND_FUEL -> "Fuel"
        QuickMessage.Kind.KIND_BREAK -> "Break"
        QuickMessage.Kind.KIND_PULL_OVER -> "Pull over"
        QuickMessage.Kind.KIND_POLICE_AHEAD -> "Police"
        QuickMessage.Kind.KIND_HAZARD_AHEAD -> "Hazard"
        QuickMessage.Kind.KIND_SLOW_DOWN -> "Slow down"
        QuickMessage.Kind.KIND_ALL_GOOD -> "All good"
        QuickMessage.Kind.KIND_UNSPECIFIED -> "Message"
    }

/** What we say and show when someone sends it: "Aki: police ahead". */
val QuickMessage.Kind.phrase: String
    get() = when (this) {
        QuickMessage.Kind.KIND_FUEL -> "needs fuel"
        QuickMessage.Kind.KIND_BREAK -> "wants a break"
        QuickMessage.Kind.KIND_PULL_OVER -> "is pulling over"
        QuickMessage.Kind.KIND_POLICE_AHEAD -> "police ahead"
        QuickMessage.Kind.KIND_HAZARD_AHEAD -> "hazard ahead"
        QuickMessage.Kind.KIND_SLOW_DOWN -> "slow down"
        QuickMessage.Kind.KIND_ALL_GOOD -> "all good"
        QuickMessage.Kind.KIND_UNSPECIFIED -> "sent a message"
    }

/** Whether a message is a warning, shown in the warning colour. */
val QuickMessage.Kind.isWarning: Boolean
    get() = this == QuickMessage.Kind.KIND_POLICE_AHEAD || this == QuickMessage.Kind.KIND_HAZARD_AHEAD || this == QuickMessage.Kind.KIND_SLOW_DOWN
