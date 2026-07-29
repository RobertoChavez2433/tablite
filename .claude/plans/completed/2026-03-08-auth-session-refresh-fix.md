# Fix: Auth Session Refresh — Recreate Game Session on Stale Token

**Date**: 2026-03-08
**Status**: IMPLEMENTED — Session 45 via /implement (3 phases, 6 gates, all PASS)
**Priority**: P0 (blocks all game login after session expires)
**Review**: `adversarial_reviews/2026-03-09-auth-session-refresh-fix/review.md`

## Problem

After ~12 days of inactivity, the OSRS login fails silently ("Failed to login. Please try again."). RuneLite cycles `LOGGING_IN -> LOGIN_SCREEN` repeatedly.

### Root Cause (Verified)

The Jagex OAuth flow has 3 steps, but the refresh flow only does Step 1:

| Step | Initial Auth | Current Refresh | Needed |
|------|-------------|-----------------|--------|
| 1. Launcher token refresh | `com_jagex_auth_desktop_launcher` | Done | Yes |
| 2. Consent flow (browser) | `1fddee4e-...` consent client | Skipped | Yes |
| 3. Create game session | POST id_token -> `/sessions` | Skipped | Yes |

**Verification results:**

| Check | Result |
|-------|--------|
| Refresh token still valid? | 200 — returns fresh Step 1 tokens |
| Step 1 access_token -> accounts API | 401 |
| JX_SESSION_ID -> accounts API | 401 (expired server-side) |
| Step 1 id_token -> create game session | 403 INVALID_ID_TOKEN |

The game session endpoint **requires** the Step 2 consent id_token. Step 2 requires browser interaction (implicit/hybrid flow with `response_type=id_token code`). There is no server-side-only way to recreate the game session.

Additionally, `JX_ACCESS_TOKEN` deployed to RuneLite is the Step 1 launcher token, not the game access token. This is wrong — but the game primarily authenticates via `JX_SESSION_ID`, so fixing the session is the critical path.

## Design

### Pre-Launch Session Validation

Add a session validation check in `SetupViewModel.performLaunch()` BEFORE deploying credentials:

```
performLaunch() flow:
  1. refreshIfNeeded()           <- existing (Step 1 refresh)
  2. validateGameSession()       <- NEW: test JX_SESSION_ID against accounts API
  3. IF Expired -> clearCredentials() + trigger GeckoView auth flow (auto-resume on completion)
     IF Valid -> proceed to launch
     IF NetworkError -> log + continue (don't clear creds)
  4. deployCredentials()         <- existing
  5. launchRuneLite()            <- existing
```

**Note**: Session validation only applies to Jagex accounts that have a `JX_SESSION_ID`. Legacy RuneScape accounts skip Step 2/3 entirely and never store a session ID, so they bypass validation via the existing `hasCredentials()` gate.

### Session Validation (New)

Add to `JagexOAuth2Manager`:

```kotlin
/** Result of pre-launch session validation. */
sealed class SessionValidation {
    /** Session is valid (200 from accounts API). */
    object Valid : SessionValidation()
    /** Session expired server-side (401/403). Must re-auth via GeckoView. */
    object Expired : SessionValidation()
    /** Network error — cannot determine session status. Don't clear creds. */
    data class NetworkError(val exception: Exception) : SessionValidation()
}

/**
 * Validate a game session by testing the sessionId against the accounts API.
 * GET /game-session/v1/accounts with Bearer sessionId.
 * Returns SessionValidation (Valid/Expired/NetworkError) — never throws.
 */
suspend fun validateSession(sessionId: String): SessionValidation
```

The tri-state return ensures the caller can distinguish "expired" (clear creds, re-auth) from "network unreachable" (log, continue with existing creds). A boolean return would conflate these, potentially destroying valid credentials on transient network failures.

### LaunchState: New ValidatingSession Variant

Add to the `LaunchState` sealed class:

```kotlin
/** Validating game session against Jagex API */
object ValidatingSession : LaunchState()
```

Usage in `performLaunch()`: set `_launchState.value = LaunchState.ValidatingSession` after refresh completes and before calling `validateSession()`. The UI displays "Checking session..." during this state.

### Auto-Trigger Auth Flow with Resume

When session is stale:

1. Set `pendingLaunchAfterAuth.set(true)` — flag to auto-resume launch after re-auth
2. `credentialManager.clearCredentials()` — wipe stored tokens (method already exists)
3. Update UI state to show "Session expired — re-authenticating..."
4. Call `startLogin()` to launch `GeckoAuthActivity` (same as first-time auth)
5. On auth completion, `handleAuthResult()` checks `pendingLaunchAfterAuth`:
   - If `true`: clear flag, call `performLaunch()` directly (skip update check + health check, already done in this session)
   - If `false`: navigate to `AppScreen.Launch` as normal

**Why `performLaunch()` not `launchRuneLite()`**: The update check and health check were already completed earlier in the same `launchRuneLite()` call. Redoing them wastes ~5-10 seconds. `performLaunch()` picks up from token refresh -> validation -> deploy -> launch.

**SetupActions null guard**: The re-auth trigger must check `orchestrator.actions != null` before calling `startLogin()`. If null (Activity paused), set `LaunchState.Failed("Activity not available — please try again")` — mirrors the existing null guard pattern in `startLogin()`.

### Key Implementation Details

**File: `JagexOAuth2Manager.kt`**
- Add `SessionValidation` sealed class (Valid/Expired/NetworkError)
- Add `validateSession(sessionId: String): SessionValidation` method
- GET to `ACCOUNTS_ENDPOINT` with `Bearer sessionId`
- Return `Valid` on 200, `Expired` on 401/403, `NetworkError` on IOException
- Use `executeCancellable()` for coroutine-aware HTTP (existing pattern)
- Re-throw `CancellationException` (coroutine safety rule)

**File: `SetupViewModel.kt`**
- Add `LaunchState.ValidatingSession` to sealed class
- Add `private val pendingLaunchAfterAuth = AtomicBoolean(false)`
- Modify `performLaunch()`:
  - After `refreshIfNeeded()`, set `LaunchState.ValidatingSession`
  - Read `sessionId` from `credentialManager.getCredentials()?.sessionId`
  - If sessionId exists: call `oAuth2Manager.validateSession(sessionId)`
    - `Valid` -> proceed
    - `Expired` -> set `pendingLaunchAfterAuth(true)`, `clearCredentials()`, call `startLogin()`, return
    - `NetworkError` -> log warning, continue (match existing `refreshIfNeeded` NetworkError behavior)
  - If no sessionId: skip validation (legacy RuneScape account path)
- Modify `handleAuthResult()`:
  - After successful auth + Step 3 game session stored:
    - Check `pendingLaunchAfterAuth.compareAndSet(true, false)`
    - If was true: call `performLaunch()` instead of navigating to `AppScreen.Launch`
    - If was false: navigate to `AppScreen.Launch` as normal
- Clear `pendingLaunchAfterAuth` in `onCleared()`, on cancel, and on error paths

**File: `CredentialManager.kt`**
- `clearCredentials()` already exists at line 194 — no changes needed
- Already clears both `inMemoryAccessToken` and persisted prefs

**File: UI (LaunchScreen or equivalent)**
- Add case for `LaunchState.ValidatingSession` -> display "Checking session..."

### UX Flow

```
User taps "Launch RuneLite"
  -> "Checking for updates..." (existing)
  -> "Running health check..." (existing)
  -> "Refreshing tokens..." (existing, if needed)
  -> "Checking session..." (NEW — ValidatingSession state)
  -> Session valid? -> "Launching RuneLite..." -> Launch
  -> Session expired?
    -> "Session expired. Signing in..." (status message)
    -> GeckoView auth opens automatically
    -> User completes Jagex login (may be quick if browser has cookies)
    -> Auth complete -> credentials stored -> Step 3 game session created
    -> pendingLaunchAfterAuth detected -> performLaunch() auto-called
    -> "Launching RuneLite..." -> Launch
```

### Edge Cases

1. **Refresh token also expired** — `refreshIfNeeded()` returns `NeedsLogin`. Navigate to Login screen. No session validation needed (user must re-auth anyway).
2. **Network error during validation** — `SessionValidation.NetworkError` returned. Don't clear creds. Log warning, continue with existing credentials (they may still work if the server is temporarily unreachable).
3. **User cancels re-auth** — `handleAuthResult()` receives `RESULT_CANCELED`. Clear `pendingLaunchAfterAuth`, return to main screen with "Login cancelled" state.
4. **Browser has expired cookies** — User sees full Jagex login form (username/password). Normal flow.
5. **Activity paused during re-auth trigger** — `orchestrator.actions` is null. Set `LaunchState.Failed("Activity not available")`.
6. **No sessionId stored (legacy RuneScape account)** — `getCredentials()?.sessionId` is null because `storeGameSession()` was never called. Skip validation, proceed directly to deploy.

### What NOT to Fix (Out of Scope)

- `JX_ACCESS_TOKEN` being the wrong token (Step 1 vs Step 2) — the game authenticates via `JX_SESSION_ID`, not `JX_ACCESS_TOKEN`. The access token in credentials.properties is used by RuneLite for its own purposes and works fine as the launcher token.
- Step 2 consent flow automation — requires browser, cannot be done silently.
- Token refresh chain optimization — current refresh works fine for Step 1.

## Files to Modify

| File | Change |
|------|--------|
| `JagexOAuth2Manager.kt` | Add `SessionValidation` sealed class + `validateSession()` method |
| `SetupViewModel.kt` | Add `LaunchState.ValidatingSession`, `pendingLaunchAfterAuth` flag, session validation in `performLaunch()`, auto-resume in `handleAuthResult()` |
| UI (LaunchScreen) | Handle `LaunchState.ValidatingSession` -> "Checking session..." |

## Testing

1. **Happy path**: Fresh tokens -> session valid -> launch works
2. **Stale session**: Old tokens -> Expired -> auto-triggers GeckoAuth -> fresh login -> `pendingLaunchAfterAuth` -> `performLaunch()` -> launch
3. **Expired refresh token**: refresh fails -> NeedsLogin -> Login screen (no session validation needed)
4. **Network error**: validation returns NetworkError -> log warning, continue with existing creds
5. **User cancels auth**: `pendingLaunchAfterAuth` cleared, return to main screen
6. **Activity not available**: actions null -> `LaunchState.Failed`
7. **Legacy RuneScape account**: no sessionId stored -> validation skipped -> deploy proceeds

## Notes

- `JX_SESSION_ID` memory note said "does NOT expire" — this is WRONG. Verified it expires after ~12 days. Update MEMORY.md.
- The `parseJwtClaim(exp)` warning is benign — Step 1 access_token is opaque (not JWT), so parsing fails. Falls back to `expires_in`. Not a bug.
