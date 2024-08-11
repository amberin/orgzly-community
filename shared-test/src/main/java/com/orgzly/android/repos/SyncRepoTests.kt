package com.orgzly.android.repos

import android.annotation.SuppressLint
import androidx.documentfile.provider.DocumentFile
import com.orgzly.android.BookName
import com.orgzly.android.util.MiscUtils
import org.junit.Assert.assertEquals
import java.io.File

@SuppressLint("NewApi")
interface SyncRepoTests {

    fun testGetBooks_singleOrgFile()
    fun testGetBooks_singleFileInSubfolder()

    companion object {

        const val repoDirName = "orgzly-android-test"
        private const val treeDocumentFileExtraSegment = "/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2F$repoDirName%2F"

        fun testGetBooks_singleOrgFile(remoteDir: Any, syncRepo: SyncRepo) {
            // Given
            val testBookContent = "\n\n...\n\n"
            val expectedRookUri: String
            when (remoteDir) {
                // N.B. Expected book name contains space
                is File -> {
                    MiscUtils.writeStringToFile(testBookContent, File(remoteDir.absolutePath + "/Book one.org"))
                    expectedRookUri = syncRepo.uri.toString() + "/Book%20one.org"
                }
                is DocumentFile -> {
                    MiscUtils.writeStringToDocumentFile(testBookContent, "Book one.org", remoteDir.uri)
                    expectedRookUri = syncRepo.uri.toString() + treeDocumentFileExtraSegment + "Book%20one.org"
                }
                else -> expectedRookUri = ""
            }

            // When
            val books = syncRepo.books
            val retrieveBookDestinationFile = kotlin.io.path.createTempFile().toFile()
            syncRepo.retrieveBook("Book one.org", retrieveBookDestinationFile)

            // Then
            assertEquals(1, books.size)
            assertEquals(expectedRookUri, books[0].uri.toString())
            assertEquals(testBookContent, retrieveBookDestinationFile.readText())
            assertEquals("Book one.org", BookName.getFileName(syncRepo.uri, books[0].uri))
        }

        fun testGetBooks_singleFileInSubfolder(remoteDir: Any, syncRepo: SyncRepo) {
            // Given
            val testBookContent = "\n\n...\n\n"
            val expectedRookUri: String
            when (remoteDir) {
                is File -> {
                    val subFolder = File(remoteDir.absolutePath + "/Folder")
                    subFolder.mkdir()
                    val remoteBookFile = File(subFolder.absolutePath + "/Book one.org")
                    MiscUtils.writeStringToFile(testBookContent, remoteBookFile)
                    expectedRookUri = syncRepo.uri.toString() + "/Folder/Book%20one.org"
                }
                is DocumentFile -> {
                    val subFolder = remoteDir.createDirectory("Folder")
                    MiscUtils.writeStringToDocumentFile(testBookContent, "Book one.org", subFolder!!.uri)
                    expectedRookUri = syncRepo.uri.toString() + treeDocumentFileExtraSegment + "Folder%2FBook%20one.org"
                }
                else -> expectedRookUri = ""
            }

            // When
            val books = syncRepo.books
            val retrieveBookDestinationFile = kotlin.io.path.createTempFile().toFile()
            syncRepo.retrieveBook("Folder/Book one.org", retrieveBookDestinationFile)

            // Then
            assertEquals(1, books.size)
            assertEquals(expectedRookUri, books[0].uri.toString())
            assertEquals("Folder/Book one.org", BookName.getFileName(syncRepo.uri, books[0].uri))
            assertEquals(testBookContent, retrieveBookDestinationFile.readText())
        }
    }
}