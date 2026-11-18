package com.gamut.core.designsystem

/**
 * Layout of the album page, picked on the Appearance screen (same idea as [FavoritesStyle]).
 *
 * - [Classic]: large cover, left-aligned title and a round play button, songs as flat rows.
 * - [Soft]: centered cover over a faint glow of the cover's own colour, Play and Shuffle pills,
 *   and the songs stacked into one rounded block like the Favorites list.
 * - [Full]: the cover runs edge to edge behind the status bar and melts into the page, with the
 *   title written over the fade and the songs as plain rows below.
 */
enum class AlbumStyle { Classic, Soft, Full }