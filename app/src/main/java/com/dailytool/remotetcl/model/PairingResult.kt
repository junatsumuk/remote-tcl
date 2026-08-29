package com.dailytool.remotetcl.model

data class PairingResult(
    val success: Boolean,
    val message: String,
    val diagnosticLog: String = "",
    val openPorts: List<Int> = emptyList()
)
