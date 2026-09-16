package io.github.moronigranja.ayvu.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.moronigranja.ayvu.persistence.BackupStore
import io.github.moronigranja.ayvu.persistence.BookFileStore
import io.github.moronigranja.ayvu.persistence.LibraryDatabase
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * E1: the backup composition root. [BookFileStore] lives under
 * `files/books` (PackModule's qualified files dir); the archive's app
 * version is the package's versionName; [BackupStore] is the one bound
 * store the settings screen talks to.
 */
@Module
@InstallIn(SingletonComponent::class)
object BackupModule {
    @Provides
    @Singleton
    fun provideBookFileStore(
        @Named("app_files_dir") filesDir: File,
    ): BookFileStore = BookFileStore(File(filesDir, "books"))

    @Provides
    @Singleton
    @Named("app_version")
    fun provideAppVersionName(
        @ApplicationContext context: Context,
    ): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    @Provides
    @Singleton
    fun provideBackupStore(
        database: LibraryDatabase,
        bookFileStore: BookFileStore,
        @Named("app_version") appVersion: String,
    ): BackupStore = BackupStore(database, appVersion, bookFileStore)
}
