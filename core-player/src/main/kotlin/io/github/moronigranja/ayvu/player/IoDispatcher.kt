package io.github.moronigranja.ayvu.player

import javax.inject.Qualifier

/**
 * Qualifier for the app-wide IO [kotlinx.coroutines.CoroutineDispatcher]
 * (production: [kotlinx.coroutines.Dispatchers.IO]; tests: a virtual
 * dispatcher). Owned by core-player (A6) so feature modules and the app
 * composition root share the annotation without a feature edge.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
