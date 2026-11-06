# Gamut logo – Android pack

## res/drawable  (VectorDrawable, API 24+; gradients need API 24+)
ic_gamut_{mark|horizontal|stacked}_{black|white}.xml     flat, ~1–5 KB
ic_gamut_mark_color.xml                                  gradient approximation of the ribbon
ic_gamut_{horizontal|stacked}_color_{darktext|lighttext}.xml
  darktext = black wordmark (light backgrounds), lighttext = white wordmark (dark backgrounds)
Copy the res/ folder into app/src/main/res/. Default sizes: mark 120dp wide, horizontal 320dp, stacked 220dp.
Override with layout_width / layout_height as needed (aspect ratio is preserved with fitCenter).

## res/drawable-*dpi  (exact ribbon, lossless WebP)
ic_gamut_mark_color_exact.webp – lossless render of the new Gamut ribbon (blue -> violet -> magenta).
Baseline 120dp wide: mdpi 120px … xxxhdpi 480px.
Use this where exact colors matter; use the VectorDrawable where size/scaling matter.

## png  (transparent masters, new logos)
mark 1600w; horizontal + stacked at 2400w, darktext/lighttext. For splash screens, store listings, etc.

## svg  (lightweight web/design versions, gradient ribbon, 5–6 KB)
Same variants as the VectorDrawables.

Note: every file in this pack is rebuilt from the new Gamut brand-kit logos (lowercase wordmark with the curvy "m";
ribbon gradient #3752DB -> #7048D8 -> #B8329E). File names are unchanged, so it drops in over the old pack. The PNG/WebP files
are renders of the same vector artwork, so they match the VectorDrawables exactly.
