package com.gamut.core.designsystem

/**
 * Layout of the artist page, picked on the Appearance screen (same idea as [AlbumStyle]).
 *
 * - [Classic]: round portrait with the name in the top bar and vertical tabs on the side.
 * - [Full]: the portrait runs edge to edge behind the status bar and melts into the page, with the
 *   name written over the fade, Play / Follow pills and a row of tab chips above one scrolling page.
 * - [Showcase]: a compact header, then the albums as big swipeable covers, with the songs, latest
 *   releases and similar artists stacked below on one page.
 */
enum class ArtistStyle { Classic, Full, Showcase }