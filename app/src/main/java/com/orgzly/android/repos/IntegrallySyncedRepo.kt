package com.orgzly.android.repos

import android.content.Context
import android.net.Uri
import com.orgzly.android.data.DataRepository
import com.orgzly.android.sync.BookNamesake
import com.orgzly.android.sync.SyncState
import java.io.File
import java.io.IOException

interface IntegrallySyncedRepo {
    @Throws(IOException::class)
    fun syncBook(uri: Uri, current: VersionedRook?, fromDB: File): TwoWaySyncResult

    @Throws(IOException::class)
    fun syncRepo(context: Context, dataRepository: DataRepository): SyncState?

    fun tryPushIfHeadDiffersFromRemote()
}