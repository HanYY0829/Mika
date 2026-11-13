package com.wangzan101.mika.capsule

/** A docked ball may tuck away; requests, speech and open cards stay fully visible. */
object CapsuleEdge {
    fun hidden(enabled: Boolean, kind: CapsuleKind, pill: Boolean, needs: Int,
        expanded: Boolean, dragging: Boolean, talking: Boolean, note: Boolean, idleFor: Long): Boolean =
        enabled && !pill && !kind.needsYou && kind != CapsuleKind.HandedOver && needs == 0 &&
            !expanded && !dragging && !talking && !note && idleFor >= CapsuleFade.IDLE_MS

    /** The visible half touches the safe physical edge, with no invisible horizontal touch area. */
    fun offset(side: DockSide, area: DockArea, room: Int): Int =
        (if (side == DockSide.Left) area.edgeLeft + room else area.width - area.edgeRight + room).coerceAtLeast(0)
}

/** Turning the capsule on needs the already-declared accessibility service; Android still asks the user. */
object CapsuleSetup {
    fun needsAccessibility(mode: CapsuleMode, serviceAvailable: Boolean): Boolean =
        mode != CapsuleMode.Off && !serviceAvailable
}

data class CapsuleAppBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Fullscreen means a main-display app covers the screen with the status bar hidden.
 * Edge-to-edge layouts with visible bars, split screen and unknown state do not qualify. */
object CapsuleFullscreen {
    fun matches(width: Int, height: Int, statusBarVisible: Boolean?, app: CapsuleAppBounds?,
        navigationInset: Int = 0, leftInset: Int = 0, topInset: Int = 0, rightInset: Int = 0): Boolean =
        statusBarVisible == false && width > 0 && height > 0 && app != null &&
            app.left <= leftInset.coerceIn(0, width / 2) && app.top <= topInset.coerceIn(0, height / 2) &&
            app.right >= width - rightInset.coerceIn(0, width / 2) &&
            app.bottom >= height - navigationInset.coerceIn(0, height / 2)
}

enum class CapsuleTileAction { TurnOff, TurnOn, OpenApp }

object CapsuleTilePolicy {
    fun action(mode: CapsuleMode, accessibility: Boolean, quit: Boolean): CapsuleTileAction = when {
        mode != CapsuleMode.Off -> CapsuleTileAction.TurnOff
        !accessibility || quit -> CapsuleTileAction.OpenApp
        else -> CapsuleTileAction.TurnOn
    }
}
