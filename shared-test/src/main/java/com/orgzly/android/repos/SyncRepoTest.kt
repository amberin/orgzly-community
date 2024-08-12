package com.orgzly.android.repos

import android.annotation.SuppressLint
import androidx.documentfile.provider.DocumentFile
import com.orgzly.android.BookName
import com.orgzly.android.util.MiscUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.Assert.assertEquals
import java.io.File

@SuppressLint("NewApi")
interface SyncRepoTest {

    fun testGetBooks_singleOrgFile()
    fun testGetBooks_singleFileInSubfolder()
    fun testGetBooks_allFilesAreIgnored()
    fun testGetBooks_specificFileInSubfolderIsIgnored()
    fun testGetBooks_specificFileIsUnignored()

        companion object {

        const val repoDirName = "orgzly-android-test"
        private const val treeDocumentFileExtraSegment = "/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2F$repoDirName%2F"

        fun testGetBooks_singleOrgFile(repoManipulationPoint: Any, syncRepo: SyncRepo) {
            // Given
            val testBookContent = "\n\n...\n\n"
            var expectedRookUri = ""
            when (syncRepo) {
                is WebdavRepo -> {
                    repoManipulationPoint as File
                    MiscUtils.writeStringToFile(
                        testBookContent,
                        File(repoManipulationPoint.absolutePath + "/Book one.org")
                    )
                    expectedRookUri = syncRepo.uri.toString() + "/Book%20one.org"
                }
                is GitRepo -> {
                    repoManipulationPoint as File
                    expectedRookUri = "/Book one.org"
                    MiscUtils.writeStringToFile(
                        testBookContent,
                        File(repoManipulationPoint.absolutePath + expectedRookUri)
                    )
                    val git = Git(
                        FileRepositoryBuilder()
                            .addCeilingDirectory(repoManipulationPoint)
                            .findGitDir(repoManipulationPoint)
                            .build()
                    )
                    git.add().addFilepattern("Book one.org").call()
                    git.commit().setMessage("").call()
                    git.push().call()
                }
                is DocumentRepo -> {
                    repoManipulationPoint as DocumentFile
                    MiscUtils.writeStringToDocumentFile(testBookContent, "Book one.org", repoManipulationPoint.uri)
                    expectedRookUri = syncRepo.uri.toString() + treeDocumentFileExtraSegment + "Book%20one.org"
                }
                is DropboxRepo -> {
                    repoManipulationPoint as DropboxClient
                    expectedRookUri = syncRepo.uri.toString() + "/Book%20one.org"
                    val tmpFile = File.createTempFile("orgzly-test-", "")
                    MiscUtils.writeStringToFile(testBookContent, tmpFile)
                    repoManipulationPoint.upload(tmpFile, syncRepo.uri, "Book one.org")
                    tmpFile.delete()
                }
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

        fun testGetBooks_singleFileInSubfolder(repoManipulationPoint: Any, syncRepo: SyncRepo) {
            // Given
            val testBookContent = "\n\n...\n\n"
            var expectedRookUri = ""
            when (syncRepo) {
                is WebdavRepo -> {
                    repoManipulationPoint as File
                    val subFolder = File(repoManipulationPoint.absolutePath + "/Folder")
                    subFolder.mkdir()
                    val remoteBookFile = File(subFolder.absolutePath + "/Book one.org")
                    MiscUtils.writeStringToFile(testBookContent, remoteBookFile)
                    expectedRookUri = syncRepo.uri.toString() + "/Folder/Book%20one.org"
                }
                is DocumentRepo -> {
                    repoManipulationPoint as DocumentFile
                    val subFolder = repoManipulationPoint.createDirectory("Folder")
                    MiscUtils.writeStringToDocumentFile(testBookContent, "Book one.org", subFolder!!.uri)
                    expectedRookUri = syncRepo.uri.toString() + treeDocumentFileExtraSegment + "Folder%2FBook%20one.org"
                }
                is GitRepo -> {
                    repoManipulationPoint as File
                    val subFolder = File(repoManipulationPoint.absolutePath + "/Folder")
                    subFolder.mkdir()
                    val remoteBookFile = File(subFolder.absolutePath + "/Book one.org")
                    MiscUtils.writeStringToFile(testBookContent, remoteBookFile)
                    expectedRookUri = "/Folder/Book one.org"
                    val git = Git(
                        FileRepositoryBuilder()
                            .addCeilingDirectory(repoManipulationPoint)
                            .findGitDir(repoManipulationPoint)
                            .build()
                    )
                    git.add().addFilepattern("Folder/Book one.org").call()
                    git.commit().setMessage("").call()
                    git.push().call()
                }
                is DropboxRepo -> {
                    repoManipulationPoint as DropboxClient
                    expectedRookUri = syncRepo.uri.toString() + "/Folder/Book%20one.org"
                    val tmpFile = kotlin.io.path.createTempFile().toFile()
                    MiscUtils.writeStringToFile(testBookContent, tmpFile)
                    repoManipulationPoint.upload(tmpFile, syncRepo.uri, "Folder/Book one.org")
                    tmpFile.delete()
                }
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

        fun testGetBooks_allFilesAreIgnored(repoManipulationPoint: Any, syncRepo: SyncRepo) {
            // Given
            val testBookContent = "..."
            val ignoreFileContent = "*\n"
            when (syncRepo) {
                is WebdavRepo -> {
                    repoManipulationPoint as File
                    val subFolder = File(repoManipulationPoint.absolutePath, "folder")
                    subFolder.mkdir()
                    val remoteBookFile = File(subFolder.absolutePath, "book one.org")
                    MiscUtils.writeStringToFile(testBookContent, remoteBookFile)
                    val ignoreFile = File(repoManipulationPoint.absolutePath, RepoIgnoreNode.IGNORE_FILE)
                    MiscUtils.writeStringToFile(ignoreFileContent, ignoreFile)
                }
                is DocumentRepo -> {
                    repoManipulationPoint as DocumentFile
                    val subFolder = repoManipulationPoint.createDirectory("folder")
                    MiscUtils.writeStringToDocumentFile(testBookContent, "book one.org", subFolder!!.uri)
                    MiscUtils.writeStringToDocumentFile(ignoreFileContent, RepoIgnoreNode.IGNORE_FILE, repoManipulationPoint.uri)
                }
                is GitRepo -> {
                    repoManipulationPoint as File
                    val subFolder = File(repoManipulationPoint.absolutePath, "folder")
                    subFolder.mkdir()
                    val remoteBookFile = File(subFolder.absolutePath, "book one.org")
                    MiscUtils.writeStringToFile(testBookContent, remoteBookFile)
                    val ignoreFile = File(repoManipulationPoint.absolutePath, RepoIgnoreNode.IGNORE_FILE)
                    MiscUtils.writeStringToFile(ignoreFileContent, ignoreFile)
                    val git = Git(
                        FileRepositoryBuilder()
                            .addCeilingDirectory(repoManipulationPoint)
                            .findGitDir(repoManipulationPoint)
                            .build()
                    )
                    git.add().addFilepattern("folder/book one.org").call()
                    git.add().addFilepattern(".orgzlyignore").call()
                    git.commit().setMessage("").call()
                    git.push().call()
                }
                is DropboxRepo -> {
                    repoManipulationPoint as DropboxClient
                    val tmpFile = kotlin.io.path.createTempFile().toFile()
                    MiscUtils.writeStringToFile(testBookContent, tmpFile)
                    repoManipulationPoint.upload(tmpFile, syncRepo.uri, "folder/book one.org")
                    MiscUtils.writeStringToFile(ignoreFileContent, tmpFile)
                    repoManipulationPoint.upload(tmpFile, syncRepo.uri, ".orgzlyignore")
                }
            }
            // When
            val books = syncRepo.books
            // Then
            assertEquals(0, books.size)
        }

        fun testGetBooks_specificFileInSubfolderIsIgnored(repoManipulationPoint: Any, syncRepo: SyncRepo) {
            // Given
            val testBookContent = "..."
            val ignoreFileContent = "folder/book one.org\n"
            when (syncRepo) {
                is WebdavRepo -> {
                    repoManipulationPoint as File
                    val subFolder = File(repoManipulationPoint.absolutePath, "folder")
                    subFolder.mkdir()
                    val remoteBookFile = File(subFolder.absolutePath, "book one.org")
                    MiscUtils.writeStringToFile(testBookContent, remoteBookFile)
                    val ignoreFile = File(repoManipulationPoint.absolutePath, RepoIgnoreNode.IGNORE_FILE)
                    MiscUtils.writeStringToFile(ignoreFileContent, ignoreFile)
                }
                is DocumentRepo -> {
                    repoManipulationPoint as DocumentFile
                    val subFolder = repoManipulationPoint.createDirectory("folder")
                    MiscUtils.writeStringToDocumentFile(testBookContent, "book one.org", subFolder!!.uri)
                    MiscUtils.writeStringToDocumentFile(ignoreFileContent, RepoIgnoreNode.IGNORE_FILE, repoManipulationPoint.uri)
                }
                is GitRepo -> {
                    repoManipulationPoint as File
                    val subFolder = File(repoManipulationPoint.absolutePath, "folder")
                    subFolder.mkdir()
                    val remoteBookFile = File(subFolder.absolutePath, "book one.org")
                    MiscUtils.writeStringToFile(testBookContent, remoteBookFile)
                    val ignoreFile = File(repoManipulationPoint.absolutePath, RepoIgnoreNode.IGNORE_FILE)
                    MiscUtils.writeStringToFile(ignoreFileContent, ignoreFile)
                    val git = Git(
                        FileRepositoryBuilder()
                            .addCeilingDirectory(repoManipulationPoint)
                            .findGitDir(repoManipulationPoint)
                            .build()
                    )
                    git.add().addFilepattern("folder/book one.org").call()
                    git.add().addFilepattern(".orgzlyignore").call()
                    git.commit().setMessage("").call()
                    git.push().call()
                }
                is DropboxRepo -> {
                    repoManipulationPoint as DropboxClient
                    val tmpFile = kotlin.io.path.createTempFile().toFile()
                    MiscUtils.writeStringToFile(testBookContent, tmpFile)
                    repoManipulationPoint.upload(tmpFile, syncRepo.uri, "folder/book one.org")
                    MiscUtils.writeStringToFile(ignoreFileContent, tmpFile)
                    repoManipulationPoint.upload(tmpFile, syncRepo.uri, ".orgzlyignore")
                }
            }
            // When
            val books = syncRepo.books
            // Then
            assertEquals(0, books.size)
        }
        fun testGetBooks_specificFileIsUnignored(repoManipulationPoint: Any, syncRepo: SyncRepo) {
            // Given
            val testBookRelativePath = "folder/book one.org"
            val testBookContent = "..."
            val ignoreFileContent = "folder/**\n!$testBookRelativePath\n"
            when (syncRepo) {
                is WebdavRepo -> {
                    repoManipulationPoint as File
                    val subFolder = File(repoManipulationPoint.absolutePath, "folder")
                    subFolder.mkdir()
                    val remoteBookFile = File(subFolder.absolutePath, "book one.org")
                    MiscUtils.writeStringToFile(testBookContent, remoteBookFile)
                    val ignoreFile = File(repoManipulationPoint.absolutePath, RepoIgnoreNode.IGNORE_FILE)
                    MiscUtils.writeStringToFile(ignoreFileContent, ignoreFile)
                }
                is DocumentRepo -> {
                    repoManipulationPoint as DocumentFile
                    val subFolder = repoManipulationPoint.createDirectory("folder")
                    MiscUtils.writeStringToDocumentFile(testBookContent, "book one.org", subFolder!!.uri)
                    MiscUtils.writeStringToDocumentFile(ignoreFileContent, RepoIgnoreNode.IGNORE_FILE, repoManipulationPoint.uri)
                }
                is GitRepo -> {
                    repoManipulationPoint as File
                    val subFolder = File(repoManipulationPoint.absolutePath, "folder")
                    subFolder.mkdir()
                    val remoteBookFile = File(subFolder.absolutePath, "book one.org")
                    MiscUtils.writeStringToFile(testBookContent, remoteBookFile)
                    val ignoreFile = File(repoManipulationPoint.absolutePath, RepoIgnoreNode.IGNORE_FILE)
                    MiscUtils.writeStringToFile(ignoreFileContent, ignoreFile)
                    val git = Git(
                        FileRepositoryBuilder()
                            .addCeilingDirectory(repoManipulationPoint)
                            .findGitDir(repoManipulationPoint)
                            .build()
                    )
                    git.add().addFilepattern(testBookRelativePath).call()
                    git.add().addFilepattern(RepoIgnoreNode.IGNORE_FILE).call()
                    git.commit().setMessage("").call()
                    git.push().call()
                }
                is DropboxRepo -> {
                    repoManipulationPoint as DropboxClient
                    val tmpFile = kotlin.io.path.createTempFile().toFile()
                    MiscUtils.writeStringToFile(testBookContent, tmpFile)
                    repoManipulationPoint.upload(tmpFile, syncRepo.uri, testBookRelativePath)
                    MiscUtils.writeStringToFile(ignoreFileContent, tmpFile)
                    repoManipulationPoint.upload(tmpFile, syncRepo.uri, RepoIgnoreNode.IGNORE_FILE)
                    tmpFile.delete()
                }
            }
            // When
            val books = syncRepo.books
            // Then
            assertEquals(1, books.size)
        }
    }
}