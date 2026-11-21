package com.example.pip

data class Detection(
    val className: String,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun getDirection(): String {
        return when {
            centerX < 0.35f -> "left"
            centerX > 0.65f -> "right"
            else -> "ahead"
        }
    }
}