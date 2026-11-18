package com.gamut.core.designsystem

/**
 * Layout of the playlist page, picked on the Appearance screen (same idea as [AlbumStyle]).
 *
 * - [Classic]: centered cover, big title, Play / Shuffle pills and a plain list of songs.
 * - [Vinyl]: the cover is a record sleeve with a record sliding out of it (it spins while this
 *   playlist is loaded in the player), and the songs are a numbered table of joined rows.
 * - [Immersive]: the cover runs edge to edge behind the status bar and fades into the page, with
 *   the title over the fade, round action buttons and plain divider-separated song rows.
 */
enum class PlaylistStyle { Classic, Vinyl, Immersive }