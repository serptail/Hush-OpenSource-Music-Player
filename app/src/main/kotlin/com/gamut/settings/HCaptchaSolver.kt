package com.gamut.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import com.hcaptcha.sdk.HCaptcha
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Web3Forms' shared hCaptcha sitekey for free plans (from their docs). Paid plans can use their own. */
private const val SITE_KEY = "50b2fe65-b00b-4b9e-ad62-3ba471098be2"

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Shows the hCaptcha challenge and returns the token, or a failure if it was closed or couldn't load. */
suspend fun Activity.solveHCaptcha(): Result<String> = suspendCancellableCoroutine { cont ->
    // The SDK shows the challenge as a dialog fragment, so it needs a FragmentActivity (MainActivity must extend it).
    if (this !is FragmentActivity) {
        cont.resume(Result.failure(IllegalStateException("Activity is not a FragmentActivity")))
        return@suspendCancellableCoroutine
    }
    HCaptcha.getClient(this).verifyWithHCaptcha(SITE_KEY)
        .addOnSuccessListener { r -> if (cont.isActive) cont.resume(Result.success(r.tokenResult)) }
        .addOnFailureListener { e -> if (cont.isActive) cont.resume(Result.failure(e)) }
}