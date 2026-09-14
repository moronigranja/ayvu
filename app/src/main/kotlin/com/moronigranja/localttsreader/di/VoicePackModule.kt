package com.moronigranja.localttsreader.di

import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.player.VoicePackDownloader
import com.moronigranja.localttsreader.tts.PackRegistry
import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import com.moronigranja.localttsreader.tts.piper.PiperEngine
import com.moronigranja.localttsreader.tts.piper.PiperPacks
import com.moronigranja.localttsreader.tts.piper.PiperVoices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * C2 composition root: the explicit voice-pack download the reader's voice
 * sheet surfaces while the ACTIVE engine's packs are missing — over the same
 * [PackRegistry] Setup and Settings use (one disk truth). Engine-aware
 * (K2, decisions #144/#156): the resolved Piper voice's model + config plus
 * the shared espeak bundle under piper-v1, Kokoro's three otherwise — the
 * sheet's download action must never fetch the wrong engine's packs.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoicePackModule {
    @Provides
    @Singleton
    fun provideVoicePackDownloader(
        registry: PackRegistry,
        appScope: CoroutineScope,
        settings: AppSettings,
    ): VoicePackDownloader =
        object : VoicePackDownloader {
            override fun requestDownload() {
                appScope.launch {
                    val prefs = settings.state.value
                    val ids =
                        if (prefs.ttsEngine == SettingsStore.PIPER_ENGINE) {
                            PiperPacks
                                .forVoice(
                                    if (prefs.voice in PiperVoices.all) prefs.voice else PiperEngine.DEFAULT_VOICE,
                                ).map { it.id } + ESPEAK_PACK_ID
                        } else {
                            listOf(KokoroPacks.model.id, KokoroPacks.voices.id, ESPEAK_PACK_ID)
                        }
                    ids.forEach { registry.download(it) }
                }
            }
        }

    private const val ESPEAK_PACK_ID = "espeak-ng"
}
