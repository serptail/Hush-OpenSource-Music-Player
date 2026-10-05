package com.gamut.core.player

import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One thing the user can adjust in the Mod panel. Declaration order = display order. */
enum class ModControl { SPEED, LINK, PITCH, BASS, REVERB_MIX, DECAY, PREDELAY, DAMPING, LOWPASS, WOBBLE, VINYL }

/**
 * Everything a mod can change. Neutral defaults = "Original" (no processing).
 *
 * Speed + pitch are applied through ExoPlayer's PlaybackParameters; everything else goes to your mod audio
 * processor (see Media3MusicPlayer.applyMod).
 */
data class ModSettings(
    /** 0.5..2.0. */
    val speed: Float = 1f,
    /** Tape / vinyl style: pitch moves with speed. This IS the sound of every one of these genres. */
    val pitchFollowsSpeed: Boolean = true,
    /** Extra pitch on top of the above, -12..+12 semitones. */
    val pitchSemitones: Float = 0f,
    /** Low-shelf gain in dB. */
    val bassDb: Float = 0f,
    /** Reverb wet amount 0..1. 0 = reverb off. */
    val reverbMix: Float = 0f,
    val reverbDecaySec: Float = 2.5f,
    val reverbPreDelayMs: Float = 20f,
    /** High-frequency damping of the reverb tail, 0..1. */
    val reverbDamping: Float = 0.5f,
    /** Low-pass cutoff. 20 000 = off. */
    val lowPassHz: Float = 20_000f,
    /** Tape wow/flutter depth in cents (100 cents = 1 semitone). 0 = off. */
    val wobbleCents: Float = 0f,
    /** Vinyl crackle / hiss level 0..1. */
    val vinylNoise: Float = 0f,
) {
    /** The pitch factor handed to ExoPlayer (1 = unchanged). */
    val pitchRatio: Float
        get() = ((if (pitchFollowsSpeed) speed else 1f) * 2f.pow(pitchSemitones / 12f)).coerceIn(0.25f, 4f)
}

private val TEMPO = setOf(ModControl.SPEED, ModControl.LINK, ModControl.PITCH, ModControl.BASS)
private val SPACE = setOf(ModControl.REVERB_MIX, ModControl.DECAY, ModControl.PREDELAY, ModControl.DAMPING)

enum class ModPreset(val label: String, val controls: Set<ModControl>) {
    ORIGINAL("Original", emptySet()),
    SPED_UP("Sped Up", TEMPO),
    SLOWED("Slowed", TEMPO),
    NIGHTCORE("Nightcore", TEMPO),
    DAYCORE("Daycore", TEMPO),
    SLOWED_REVERB("Slowed + Reverb", TEMPO + SPACE),
    ULTRA_SLOWED("Ultra Slowed", TEMPO + SPACE),
    LO_FI(
        "Lo-Fi",
        setOf(ModControl.SPEED, ModControl.LOWPASS, ModControl.WOBBLE, ModControl.VINYL, ModControl.BASS, ModControl.REVERB_MIX),
    ),
    CUSTOM("Custom", ModControl.entries.toSet());

    /**
     * Starting values. Speed/pitch follow how these edits are actually made (a tape / 45-vs-33 RPM style rate
     * change); the rest are the usual producer settings for each sound.
     */
    fun defaults(): ModSettings = when (this) {
        ORIGINAL, CUSTOM -> ModSettings()
        // "Sped up": the mild version of nightcore, about +2.4 semitones.
        SPED_UP -> ModSettings(speed = 1.15f, bassDb = 1f)
        // Nightcore: roughly +25..35% (Wikipedia: ~35%). 1.30x is about +4.5 semitones. The usual correction for the
        // thinner low end is a small bass lift.
        NIGHTCORE -> ModSettings(speed = 1.30f, bassDb = 2f)
        SLOWED -> ModSettings(speed = 0.85f)
        // Daycore = nightcore mirrored (1 / 1.30 = 0.77), a little darker.
        DAYCORE -> ModSettings(speed = 0.75f, lowPassHz = 12_000f)
        // Slowed + reverb: 70-80% speed, ~3 s tail, short pre-delay, damped highs, about a third wet.
        SLOWED_REVERB -> ModSettings(
            speed = 0.80f, reverbMix = 0.35f, reverbDecaySec = 3f, reverbPreDelayMs = 25f, reverbDamping = 0.55f,
        )
        ULTRA_SLOWED -> ModSettings(
            speed = 0.60f, bassDb = 2f, reverbMix = 0.45f, reverbDecaySec = 5f, reverbPreDelayMs = 40f, reverbDamping = 0.6f,
        )
        // Lo-fi: a touch slower, rolled-off top end, tape wobble, a little vinyl dust, a short small-room reverb.
        LO_FI -> ModSettings(
            speed = 0.95f, bassDb = 2f, lowPassHz = 4_500f, wobbleCents = 12f, vinylNoise = 0.25f,
            reverbMix = 0.10f, reverbDecaySec = 0.8f, reverbPreDelayMs = 10f, reverbDamping = 0.7f,
        )
    }
}

/** App-wide mod state. The player observes it and applies it; the Mod panel edits it. */
object ModController {
    data class State(val preset: ModPreset = ModPreset.ORIGINAL, val settings: ModSettings = ModSettings())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Picks a preset. "Custom" keeps whatever is currently set, as a starting point. */
    fun select(preset: ModPreset) = _state.update { cur ->
        if (preset == ModPreset.CUSTOM) State(preset, cur.settings) else State(preset, preset.defaults())
    }

    /** Tweaks the current preset's values (the preset stays selected; [reset] goes back to its defaults). */
    fun edit(transform: (ModSettings) -> ModSettings) = _state.update { it.copy(settings = transform(it.settings)) }

    fun reset() = _state.update { it.copy(settings = it.preset.defaults()) }
}