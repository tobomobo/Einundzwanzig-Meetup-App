package space.einundzwanzig.meetup.data.identity.profile

import kotlinx.coroutines.flow.Flow

/**
 * Loads and saves [UserProfile] in the shared prefs (Dart `UserProfile.load/save`).
 *
 * Pref keys: `nickname`, `full_name`, `telegram`, `nostr`, `twitter`, `nostr_verified`,
 * `admin_verified`, `is_admin`, `admin_via_portal`, `admin_via_vouch`, `admin_via_seed`,
 * `review_demo`, `home_meetup`, `favorite_meetups`, `promotion_source`.
 */
interface UserProfileRepository {
    /** Dart `UserProfile.load()` with the exact precedence rules (spec 01 §3.2). */
    suspend fun load(): UserProfile

    /** Dart `save()`: writes **all** keys (including the derived `is_admin`/`promotion_source`) atomically. */
    suspend fun save(profile: UserProfile)

    /** `load()` → [transform] → `save()`; returns the saved profile. */
    suspend fun update(transform: (UserProfile) -> UserProfile): UserProfile

    /**
     * The profile now and after every change of its prefs (signing-mode changes included), every
     * [save] and every local key write/delete. A secure store that is unreadable at that moment is
     * logged and skipped (the flow keeps its last value and never fails); [load] reports it.
     */
    val profile: Flow<UserProfile>
}
