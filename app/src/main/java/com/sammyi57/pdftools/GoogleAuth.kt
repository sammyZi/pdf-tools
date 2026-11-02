package com.sammyi57.pdftools

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.os.Build
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

// Google's Authorization API: asks for the Drive "files this app created" scope and returns a short-lived access token.
// Tokens are always requested for a specific account, so background uploads can't silently use a different account.
// Needs an Android OAuth client (package + signing SHA-1) in the Google Cloud project; no client ID lives in the app.
object GoogleAuth {
  private val scopes = listOf(Scope("https://www.googleapis.com/auth/drive.file"))
  private val client get() = Identity.getAuthorizationClient(appContext)

  /** Shows the system account picker (always, even with one account) and returns the chosen email. */
  suspend fun chooseAccount(): String {
    val types = arrayOf("com.google")
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      AccountManager.newChooseAccountIntent(null, null, types, null, null, null, null)
    } else {
      @Suppress("DEPRECATION")
      AccountManager.newChooseAccountIntent(null, null, types, true, null, null, null, null)
    }
    val r = awaitResult(ActivityResultContracts.StartActivityForResult(), intent)
    if (r.resultCode != Activity.RESULT_OK) throw UserError(appContext.getString(R.string.error_google_signin_cancelled))
    return r.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: throw UserError(appContext.getString(R.string.error_google_no_account_chosen))
  }

  class NeedsConsent(email: String) : Exception(appContext.getString(R.string.error_drive_needs_consent, email))

  /** A drive.file token for exactly this account. Non-interactive never shows UI: it throws NeedsConsent instead. */
  suspend fun token(email: String, interactive: Boolean): String {
    val request = AuthorizationRequest.builder().setRequestedScopes(scopes).setAccount(Account(email, "com.google")).build()
    val result = try { client.authorize(request).await() } catch (e: Exception) { throw UserError(describe(e)) }
    if (!result.hasResolution()) return result.accessToken ?: throw UserError(appContext.getString(R.string.error_google_signin_failed))
    val pending = result.pendingIntent
    if (!interactive || pending == null) throw NeedsConsent(email)
    val r = awaitResult(ActivityResultContracts.StartIntentSenderForResult(), IntentSenderRequest.Builder(pending).build())
    if (r.resultCode != Activity.RESULT_OK) throw UserError(appContext.getString(R.string.error_google_signin_cancelled))
    return try {
      client.getAuthorizationResultFromIntent(r.data).accessToken ?: throw UserError(appContext.getString(R.string.error_google_signin_failed))
    } catch (e: ApiException) {
      throw UserError(describe(e))
    }
  }

  /** Drops a token Google's cache still hands out after the server rejected it (401), or on sign-out. */
  suspend fun clearToken(token: String) {
    runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
  }

  private fun describe(e: Exception): String =
    if (e is ApiException && e.statusCode == 10) {
      appContext.getString(R.string.error_google_oauth_not_configured)
    } else {
      e.message ?: appContext.getString(R.string.error_google_signin_failed)
    }
}
