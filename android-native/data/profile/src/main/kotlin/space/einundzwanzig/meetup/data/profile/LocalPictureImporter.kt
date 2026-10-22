package space.einundzwanzig.meetup.data.profile

/**
 * Imports a gallery image as the locally picked profile picture (`local_profile_picture`,
 * spec 07 §8.8, spec 12 F14). Shared by the profile screen and the home header.
 */
interface LocalPictureImporter {

    /**
     * Copies the image behind [uri] (a `content://` / `file://` URI string from the photo picker)
     * into app storage, scaled like Flutter's `pickImage(maxWidth: 400, maxHeight: 400,
     * imageQuality: 80)` (≤ [MAX_SIZE] px, JPEG q[QUALITY]), stores it with
     * [NostrProfileService.setLocalPicture], and only then deletes older picked pictures.
     * Returns the new absolute path. Throws if the image cannot be read or stored; the
     * previous picture then stays untouched.
     */
    suspend fun import(uri: String): String

    companion object {
        /** Longest edge of the stored picture. */
        const val MAX_SIZE: Int = 400

        /** JPEG quality of the stored picture. */
        const val QUALITY: Int = 80

        /** Directory below `filesDir` that holds the picked picture. */
        const val DIR: String = "profile_picture"
    }
}
