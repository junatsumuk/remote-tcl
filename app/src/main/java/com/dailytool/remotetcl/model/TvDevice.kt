package com.dailytool.remotetcl.model

enum class TvType {
    ANDROID_TV,
    ROKU_TV,
    UNKNOWN
}

data class TvDevice(
    val name: String,
    val ipAddress: String,
    val port: Int = 0,
    val type: TvType = TvType.UNKNOWN,
    val isPaired: Boolean = false
)
