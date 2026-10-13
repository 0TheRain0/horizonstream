package com.cmsoft.horizonstream.session

/** Carries releases across menu/focus changes without blocking unrelated controls. */
internal class QuestInputReleaseGate {
    internal data class Sample(
        val leftX: Float, val leftY: Float, val rightX: Float, val rightY: Float,
        val leftTrigger: Float, val rightTrigger: Float,
        val leftGrip: Float, val rightGrip: Float, val buttons: UInt
    )

    private var blockedButtons = UInt.MAX_VALUE
    private var leftStickBlocked = true
    private var rightStickBlocked = true
    private var leftTriggerBlocked = true
    private var rightTriggerBlocked = true
    private var leftGripBlocked = true
    private var rightGripBlocked = true

    @Synchronized
    fun arm() {
        blockedButtons = UInt.MAX_VALUE
        leftStickBlocked = true
        rightStickBlocked = true
        leftTriggerBlocked = true
        rightTriggerBlocked = true
        leftGripBlocked = true
        rightGripBlocked = true
    }

    @Synchronized
    fun filter(sample: Sample): Sample {
        // Each released button is rearmed separately. A held Menu/Grip must
        // never stop an independently pressed A/B button from working.
        blockedButtons = blockedButtons and sample.buttons
        if(kotlin.math.abs(sample.leftX) < 0.2f && kotlin.math.abs(sample.leftY) < 0.2f)
            leftStickBlocked = false
        if(kotlin.math.abs(sample.rightX) < 0.2f && kotlin.math.abs(sample.rightY) < 0.2f)
            rightStickBlocked = false
        if(sample.leftTrigger < 0.1f) leftTriggerBlocked = false
        if(sample.rightTrigger < 0.1f) rightTriggerBlocked = false
        if(sample.leftGrip < 0.55f) leftGripBlocked = false
        if(sample.rightGrip < 0.55f) rightGripBlocked = false
        return Sample(
            if(leftStickBlocked) 0f else sample.leftX,
            if(leftStickBlocked) 0f else sample.leftY,
            if(rightStickBlocked) 0f else sample.rightX,
            if(rightStickBlocked) 0f else sample.rightY,
            if(leftTriggerBlocked) 0f else sample.leftTrigger,
            if(rightTriggerBlocked) 0f else sample.rightTrigger,
            if(leftGripBlocked) 0f else sample.leftGrip,
            if(rightGripBlocked) 0f else sample.rightGrip,
            sample.buttons and blockedButtons.inv()
        )
    }
}
