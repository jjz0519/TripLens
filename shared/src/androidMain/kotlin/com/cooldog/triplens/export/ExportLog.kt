package com.cooldog.triplens.export

import android.util.Log

internal actual fun exportLogI(tag: String, message: String) {
    Log.i(tag, message)
}

internal actual fun exportLogE(tag: String, message: String, cause: Throwable) {
    Log.e(tag, message, cause)
}
