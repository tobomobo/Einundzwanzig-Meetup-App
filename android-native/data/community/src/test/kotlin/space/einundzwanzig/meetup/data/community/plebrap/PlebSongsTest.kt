package space.einundzwanzig.meetup.data.community.plebrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlebSongsTest {

    private val songs = PlebSongs.all

    @Test
    fun catalogHas41SongsInAlbumOrder() {
        assertEquals(41, songs.size)
        assertEquals(40, PlebSongs.lastIndex)
        assertEquals(listOf("Money Fest", "Proof of Word EP", "Pleb2Pleb EP", "Singles"), PlebSongs.albums)
        assertEquals(listOf(12, 8, 7, 14), PlebSongs.albums.map { a -> PlebSongs.byAlbum.getValue(a).size })
        assertEquals(listOf(0, 12, 20, 27), PlebSongs.albums.map { a -> PlebSongs.byAlbum.getValue(a).first().index })
    }

    @Test
    fun entriesMatchDartVerbatim() {
        assertEquals(
            PlebSong(
                "Money Fest", "Money Fest", "TooBitToFail",
                "https://plebrap.de/.cm4all/uproc.php/0/01-Money%20Fest-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e4484b03",
            ),
            songs[0],
        )
        assertEquals("SHA 'o' lin", songs[3].title)
        assertEquals(
            "https://plebrap.de/.cm4all/uproc.php/0/04-SHA%20%27o%27%20lin-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e456e3fe",
            songs[3].url,
        )
        assertEquals("Don't trust, verify", songs[8].title)
        assertEquals(
            "https://plebrap.de/.cm4all/uproc.php/0/09-Don%60t%20trust%2C%20verify-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e45110b1",
            songs[8].url,
        )
        assertEquals("Modern Märchen Token", songs[2].title)
        assertTrue(songs[2].url.contains("Ma%CC%88rchen"))
        assertEquals("Das Geldsystem ist krank", songs[17].title)
        assertEquals("TooBitToFail, Beat: Meidi", songs[17].artist)
        assertEquals(
            "https://plebrap.de/.cm4all/uproc.php/0/Was%20ist%20Geld%20fu%CC%88r%20dich%20-%20JustAnotherNode.mp3?cdp=a&_=191148ce0f4",
            songs[21].url,
        )
        assertEquals(
            "https://plebrap.de/.cm4all/uproc.php/0/Fiat%20unter%20%20Feuer%20-%20TooBitToFail%20-%20Hanspanzer.mp3?cdp=a&_=18f97d95bb6",
            songs[30].url,
        )
        assertEquals(
            PlebSong(
                "Singles", "Papa hodlt", "TooBitToFail feat. JustAnotherNode",
                "https://plebrap.de/.cm4all/uproc.php/0/Papa-Hodlt-TooBitToFail-JustAnotherNode.mp3?cdp=a&_=18e0e9b0261",
            ),
            songs[40],
        )
        assertTrue(songs.all { it.url.startsWith("https://plebrap.de/.cm4all/uproc.php/0/") && it.url.contains("?cdp=a&_=") })
        assertEquals(41, songs.map { it.url }.toSet().size)
    }

    @Test
    fun coverByAlbum() {
        assertEquals("https://plebrap.de/.cm4all/uproc.php/0/.video_2024-11-01_13-33-06.mp4/poster?_=192f3022949", songs[0].cover)
        assertEquals("https://plebrap.de/.cm4all/uproc.php/0/.Pleb2Pleb_1.jpg/picture-200?_=18df683959a", songs[20].cover)
        assertEquals("https://plebrap.de/.cm4all/uproc.php/0/photo_2022-08-08_13-08-28.jpg", songs[12].cover)
        assertEquals("https://plebrap.de/.cm4all/uproc.php/0/photo_2022-08-08_13-08-28.jpg", songs[27].cover)
    }
}
