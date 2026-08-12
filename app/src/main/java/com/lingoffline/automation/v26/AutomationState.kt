package com.lingoffline.automation.v26

object AutomationState {
    @Volatile
    var autoExecutionEnabled: Boolean = false

    @Volatile
    var statusText: String = "idle"

    @Volatile
    var lastDetectorSummary: String = "belum ada frame"
}
