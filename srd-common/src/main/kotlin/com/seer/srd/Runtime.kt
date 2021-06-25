package com.seer.srd

private var taskCreatingPaused = false
private var taskProcessingPaused = false

fun isTaskCreatingPaused(): Boolean {
    return taskCreatingPaused
}

fun setTaskCreatingPaused(paused: Boolean) {
    taskCreatingPaused = paused
}

fun isTaskProcessingPaused(): Boolean {
    return taskProcessingPaused
}

fun setTaskProcessingPaused(paused: Boolean) {
    taskProcessingPaused = paused
}
