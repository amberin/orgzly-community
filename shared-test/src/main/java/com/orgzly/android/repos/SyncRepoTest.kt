package com.orgzly.android.repos

import android.annotation.SuppressLint
import androidx.documentfile.provider.DocumentFile
import com.orgzly.android.BookName
import com.orgzly.android.util.MiscUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

@SuppressLint("NewApi")
interface SyncRepoTest {

    fun testGetBooks_singleOrgFile()
    fun testGetBooks_singleFileInSubfolder()
    fun testGetBooks_allFilesAreIgnored()
    fun testGetBooks_specificFileInSubfolderIsIgnored()
    fun testGetBooks_specificFileIsUnignored()
    fun testGetBooks_ignoredExtensions()
    fun testStoreBook_expectedUri()
    fun testStoreBook_producesSameUriAsRetrieveBook()
    fun testStoreBook_producesSameUriAsGetBooks()
    fun testStoreBook_inSubfolder()
    fun testRenameBook_expectedUri()

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
                    git.add().addFilepattern(".").call()
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
                    val tmpFile = kotlin.io.path.createTempFile().toFile()
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
                    git.add().addFilepattern(".").call()
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
                    git.add().addFilepattern(".").call()
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
                    git.add().addFilepattern(".").call()
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
                    git.add().addFilepattern(".").call()
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

        fun testGetBooks_ignoredExtensions(repoManipulationPoint: Any, syncRepo: SyncRepo) {
            // Given
            val testBookContent = "\n\n...\n\n"
            when (syncRepo) {
                is WebdavRepo -> {
                    repoManipulationPoint as File
                    for (fileName in arrayOf("file one.txt", "file two.o", "file three.org")) {
                        MiscUtils.writeStringToFile(testBookContent, File(repoManipulationPoint.absolutePath, fileName))
                    }
                }
                is GitRepo -> {
                    repoManipulationPoint as File
                    for (fileName in arrayOf("file one.txt", "file two.o", "file three.org")) {
                        MiscUtils.writeStringToFile(testBookContent, File(repoManipulationPoint.absolutePath, fileName))
                    }
                    val git = Git(
                        FileRepositoryBuilder()
                            .addCeilingDirectory(repoManipulationPoint)
                            .findGitDir(repoManipulationPoint)
                            .build()
                    )
                    git.add().addFilepattern(".").call()
                    git.commit().setMessage("").call()
                    git.push().call()
                }
                is DocumentRepo -> {
                    repoManipulationPoint as DocumentFile
                    for (fileName in arrayOf("file one.txt", "file two.o", "file three.org")) {
                        MiscUtils.writeStringToDocumentFile(testBookContent, fileName, repoManipulationPoint.uri)
                    }
                }
                is DropboxRepo -> {
                    repoManipulationPoint as DropboxClient
                    val tmpFile = kotlin.io.path.createTempFile().toFile()
                    for (fileName in arrayOf("file one.txt", "file two.o", "file three.org")) {
                        MiscUtils.writeStringToFile(testBookContent, tmpFile)
                        repoManipulationPoint.upload(tmpFile, syncRepo.uri, fileName)
                    }
                    tmpFile.delete()
                }
            }
            // When
            val books = syncRepo.books
            // Then
            assertEquals(1, books.size.toLong())
            assertEquals("file three", BookName.fromFileName(BookName.getFileName(syncRepo.uri, books[0].uri)).name)
        }

        fun testStoreBook_expectedUri(syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            MiscUtils.writeStringToFile("...", tmpFile)
            // When
            val vrook = syncRepo.storeBook(tmpFile, "Book one.org")
            tmpFile.delete()
            // Then
            val expectedRookUri = when (syncRepo) {
                is GitRepo -> "/Book one.org"
                is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "Book%20one.org"
                else -> syncRepo.uri.toString() + "/Book%20one.org"
            }
            assertEquals(expectedRookUri, vrook.uri.toString())
        }

        fun testStoreBook_producesSameUriAsRetrieveBook(syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            val repositoryPath = "a folder/a book.org"
            MiscUtils.writeStringToFile("...", tmpFile)
            // When
            val storedRook = syncRepo.storeBook(tmpFile, repositoryPath)
            val retrievedBook = syncRepo.retrieveBook(repositoryPath, tmpFile)
            tmpFile.delete()
            // Then
            assertEquals(retrievedBook.uri, storedRook.uri)
        }

        fun testStoreBook_producesSameUriAsGetBooks(repoManipulationPoint: Any, syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            val repositoryPath = "a folder/a book.org"
            when (syncRepo) {
                is WebdavRepo -> {
                    repoManipulationPoint as File
                    val repoSubDir = File(repoManipulationPoint.absolutePath, "a folder")
                    repoSubDir.mkdir()
                    MiscUtils.writeStringToFile("...", File(repoSubDir, "a book.org"))
                }
                is GitRepo -> {
                    repoManipulationPoint as File
                    val repoSubDir = File(repoManipulationPoint.absolutePath, "a folder")
                    repoSubDir.mkdir()
                    MiscUtils.writeStringToFile("...", File(repoSubDir, "a book.org"))
                    val git = Git(
                        FileRepositoryBuilder()
                            .addCeilingDirectory(repoManipulationPoint)
                            .findGitDir(repoManipulationPoint)
                            .build()
                    )
                    git.add().addFilepattern(".").call()
                    git.commit().setMessage("").call()
                    git.push().call()
                }
                is DropboxRepo -> {
                    repoManipulationPoint as DropboxClient
                    MiscUtils.writeStringToFile("...", tmpFile)
                    repoManipulationPoint.upload(tmpFile, syncRepo.uri, repositoryPath)
                }
                is DocumentRepo -> {
                    repoManipulationPoint as DocumentFile
                    val subFolder = repoManipulationPoint.createDirectory("a folder")
                    MiscUtils.writeStringToDocumentFile("...", "a book.org", subFolder!!.uri)
                }
            }
            // When
            val gottenBook = syncRepo.books[0]
            MiscUtils.writeStringToFile("......", tmpFile) // N.B. Different content to ensure the repo file is actually changed
            val storedRook = syncRepo.storeBook(tmpFile, repositoryPath)
            tmpFile.delete()
            // Then
            assertEquals(gottenBook.uri, storedRook.uri)
        }

        fun testStoreBook_inSubfolder(repoManipulationPoint: Any, syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            val repositoryPath = "A folder/A book.org"
            val testBookContent = "\n\n...\n\n"
            MiscUtils.writeStringToFile(testBookContent, tmpFile)
            // When
            syncRepo.storeBook(tmpFile, repositoryPath)
            tmpFile.delete()
            // Then
            when (syncRepo) {
                is WebdavRepo -> {
                    repoManipulationPoint as File
                    val subFolder = File(repoManipulationPoint, "A folder")
                    assertTrue(subFolder.exists())
                    val bookFile = File(subFolder, "A book.org")
                    assertTrue(bookFile.exists())
                    assertEquals(testBookContent, bookFile.readText())
                }
                is GitRepo -> {
                    repoManipulationPoint as File
                    val git = Git(
                        FileRepositoryBuilder()
                            .addCeilingDirectory(repoManipulationPoint)
                            .findGitDir(repoManipulationPoint)
                            .build()
                    )
                    git.pull().call()
                    val subFolder = File(repoManipulationPoint, "A folder")
                    assertTrue(subFolder.exists())
                    val bookFile = File(subFolder, "A book.org")
                    assertTrue(bookFile.exists())
                    assertEquals(testBookContent, bookFile.readText())
                }
                is DocumentRepo -> {
                    repoManipulationPoint as DocumentFile
                    val subFolder = repoManipulationPoint.findFile("A folder")
                    assertTrue(subFolder!!.exists())
                    assertTrue(subFolder.isDirectory)
                    val bookFile = subFolder.findFile("A book.org")
                    assertTrue(bookFile!!.exists())
                    assertEquals(testBookContent, MiscUtils.readStringFromDocumentFile(bookFile))
                }
                is DropboxRepo -> {
                    // Not really much to assert here; we don't really care how Dropbox implements things,
                    // as long as URLs work as expected.
                    repoManipulationPoint as DropboxClient
                    val retrievedFile = kotlin.io.path.createTempFile().toFile()
                    repoManipulationPoint.download(syncRepo.uri, repositoryPath, retrievedFile)
                    assertEquals(testBookContent, retrievedFile.readText())
                }
            }
        }

        fun testRenameBook_expectedUri(syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            val oldFileName = "Original book.org"
            val newBookName = "Renamed book"
            val testBookContent = "\n\n...\n\n"
            MiscUtils.writeStringToFile(testBookContent, tmpFile)
            // When
            val originalVrook = syncRepo.storeBook(tmpFile, oldFileName)
            tmpFile.delete()
            syncRepo.renameBook(originalVrook.uri, newBookName)
            // Then
            val renamedVrook = syncRepo.books[0]
            val expectedRookUri = when (syncRepo) {
                is GitRepo -> "/Renamed book.org"
                is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "Renamed%20book.org"
                else -> syncRepo.uri.toString() + "/Renamed%20book.org"
            }
            assertEquals(expectedRookUri, renamedVrook.uri.toString())
        }
    }
}