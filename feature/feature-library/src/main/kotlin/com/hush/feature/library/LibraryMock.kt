package com.hush.feature.library

// ---------------------------------------------------------------------------------------------
//  MOCK DATA for the Library / playlist / artist screens. Covers and portraits are random but stable
//  picsum.photos images, names are made up. Replace LibraryMock with real data (database + network) later;
//  the screens only read these small data classes.
// ---------------------------------------------------------------------------------------------

internal data class LibSong(val id: String, val title: String, val artist: String, val imageUrl: String)

internal data class LibAlbum(val id: String, val title: String, val year: String, val imageUrl: String)

internal data class LibPlaylist(
    val id: String,
    val name: String,
    val songs: List<LibSong>,
    /** The "Liked songs" collection: shown with a heart and a filled heart on every row. */
    val isLiked: Boolean = false,
)

internal data class LibArtist(
    val id: String,
    val name: String,
    val imageUrl: String,
    val listeners: String,
    val songs: List<LibSong>,
    val albums: List<LibAlbum>,
    val singles: List<LibSong>,
    /** Their songs that are in the user's library. */
    val inLibrary: List<LibSong>,
)

private fun pic(seed: String, size: Int = 600) = "https://picsum.photos/seed/$seed/$size/$size"

internal object LibraryMock {
    private val artistNames = listOf("Ada Lune", "Velvet Static", "Noor", "Mira Vale", "Pale Coast", "Tomas Reyes")
    private val listeners = listOf("4,218,904", "1,902,336", "867,120", "2,540,771", "310,458", "1,115,092")
    private val songTitles = listOf(
        listOf("Afterglow Drive", "Borrowed Light", "Low Tide", "Paper Moons", "Blue Hour"),
        listOf("Soft Machinery", "Fade to Blue", "Slow Bloom", "Static Rain", "Neon Quiet"),
        listOf("Lantern Season", "Small Hours", "Glass Garden", "Sunday Slow", "Honey Static"),
        listOf("Salt & Silk", "Ninety Rooms", "Quiet Thunder", "Gold Hour", "Velvet Tide"),
        listOf("Slow Motion Rain", "Window Seat", "Harbour Lights", "Night Swim", "Paper Sky"),
        listOf("Night Bus Poems", "Half Asleep", "Stay Awhile", "Paper Planes", "Last Light"),
    )
    private val albumTitles = listOf(
        listOf("Slow Bloom", "Paper Moons", "Blue Hour"),
        listOf("Static Rain", "Neon Quiet", "Soft Machinery"),
        listOf("Glass Garden", "Small Hours", "Lantern Season"),
        listOf("Ninety Rooms", "Gold Hour", "Salt & Silk"),
        listOf("Harbour Lights", "Window Seat", "Night Swim"),
        listOf("Night Bus Poems", "Last Light", "Stay Awhile"),
    )

    val artists: List<LibArtist> = artistNames.mapIndexed { i, name ->
        val songs = songTitles[i].mapIndexed { j, title -> LibSong("s$i$j", title, name, pic("hush-song-$i$j")) }
        LibArtist(
            id = "artist-$i",
            name = name,
            imageUrl = pic("hush-artist-$i", 800),
            listeners = listeners[i],
            songs = songs,
            albums = albumTitles[i].mapIndexed { j, t -> LibAlbum("a$i$j", t, (2019 + j + i % 3).toString(), pic("hush-album-$i$j")) },
            singles = songs.take(3).mapIndexed { j, s -> s.copy(id = "x$i$j", imageUrl = pic("hush-single-$i$j")) },
            inLibrary = songs.take(3),
        )
    }

    private val allSongs: List<LibSong> = artists.flatMap { it.songs }

    val playlists: List<LibPlaylist> = listOf(
        LibPlaylist("liked", "Liked songs", allSongs.filterIndexed { i, _ -> i % 2 == 0 }, isLiked = true),
        LibPlaylist("p-night", "Late Night Drive", allSongs.drop(2).take(9)),
        LibPlaylist("p-rain", "Rainy Window", allSongs.drop(7).take(8)),
        LibPlaylist("p-focus", "Deep Focus", allSongs.drop(11).take(10)),
        LibPlaylist("p-sunday", "Sunday Slow", allSongs.takeLast(7)),
    )

    /** Artists the user follows. Snapshot state, so every screen reading it recomposes when it changes. */
    val followedIds = androidx.compose.runtime.mutableStateListOf("artist-0", "artist-2", "artist-3")

    fun toggleFollow(id: String) {
        if (!followedIds.remove(id)) followedIds.add(id)
    }

    fun playlist(id: String): LibPlaylist? = playlists.firstOrNull { it.id == id }
    fun artist(id: String): LibArtist? = artists.firstOrNull { it.id == id }
}