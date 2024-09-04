package com.orgzly.android.repos

import android.annotation.SuppressLint
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.orgzly.android.BookName
import com.orgzly.android.util.MiscUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException

@SuppressLint("NewApi")
interface SyncRepoTest {

    var syncRepo: SyncRepo
    val repoManipulationPoint: Any

    fun writeFileToRepo(content: String, repoRelativePath: String): String

    @Test
    fun testGetBooks_singleOrgFile() {
        // Given
        val fileContent = "\n\n...\n\n"
        val fileName = "Book one.org"
        val expectedRookUri = writeFileToRepo(fileContent, fileName)

        // When
        val retrieveBookDestinationFile = kotlin.io.path.createTempFile().toFile()
        syncRepo.retrieveBook(fileName, retrieveBookDestinationFile)
        val books = syncRepo.books

        // Then
        assertEquals(1, books.size)
        assertEquals(expectedRookUri, books[0].uri.toString())
        assertEquals(fileContent, retrieveBookDestinationFile.readText())
        assertEquals(fileName, BookName.getRepoRelativePath(syncRepo.uri, books[0].uri))
    }

    @Test
    fun testGetBooks_singleFileInSubfolder() {
        // Given
        val repoFilePath = "Folder/Book one.org"
        val fileContent = "\n\n...\n\n"
        val expectedRookUri = writeFileToRepo(fileContent, "Folder/Book one.org")

        // When
        val retrieveBookDestinationFile = kotlin.io.path.createTempFile().toFile()
        syncRepo.retrieveBook(repoFilePath, retrieveBookDestinationFile)
        val books = syncRepo.books

        // Then
        assertEquals(1, books.size)
        assertEquals(expectedRookUri, books[0].uri.toString())
        assertEquals(repoFilePath, BookName.getRepoRelativePath(syncRepo.uri, books[0].uri))
        assertEquals(fileContent, retrieveBookDestinationFile.readText())
    }

    @Test
    fun testGetBooks_allFilesAreIgnored() {
        // Given
        val ignoreFileContent = "*\n"
        writeFileToRepo("...", "folder/book one.org")
        writeFileToRepo(ignoreFileContent, RepoIgnoreNode.IGNORE_FILE)
        // When
        val books = syncRepo.books
        // Then
        assertEquals(0, books.size)
    }

    @Test
    fun testGetBooks_specificFileInSubfolderIsIgnored() {
        // Given
        val ignoreFileContent = "folder/book one.org\n"
        writeFileToRepo("...", "folder/book one.org")
        writeFileToRepo(ignoreFileContent, RepoIgnoreNode.IGNORE_FILE)
        // When
        val books = syncRepo.books
        // Then
        assertEquals(0, books.size)
    }

    @Test
    fun testGetBooks_specificFileIsUnignored() {
        // Given
        val folderName = "My Folder"
        val fileName = "My file.org"
        val ignoreFileContent = "folder/**\n!$folderName/$fileName\n"
        writeFileToRepo("...", "$folderName/$fileName")
        writeFileToRepo(ignoreFileContent, RepoIgnoreNode.IGNORE_FILE)
        // When
        val books = syncRepo.books
        // Then
        assertEquals(1, books.size)
    }

    @Test
    fun testGetBooks_ignoredExtensions() {
        // Given
        val testBookContent = "\n\n...\n\n"
        for (fileName in arrayOf("file one.txt", "file two.o", "file three.org")) {
            writeFileToRepo(testBookContent, fileName)
        }
        // When
        val books = syncRepo.books
        // Then
        assertEquals(1, books.size.toLong())
        assertEquals("file three", BookName.fromRepoRelativePath(BookName.getRepoRelativePath(syncRepo.uri, books[0].uri)).name)
    }

    @Test
    fun testStoreBook_expectedUri() {
        // Given
        val tmpFile = kotlin.io.path.createTempFile().toFile()
        MiscUtils.writeStringToFile("...", tmpFile)
        // When
        val vrook = syncRepo.storeBook(tmpFile, "Book one.org")
        tmpFile.delete()
        // Then
        val expectedRookUri = when (syncRepo) {
            is GitRepo -> "Book%20one.org"
            is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "Book%20one.org"
            else -> syncRepo.uri.toString() + "/Book%20one.org"
        }
        assertEquals(expectedRookUri, vrook.uri.toString())
    }

    @Test
    fun testStoreBook_producesSameUriAsRetrieveBook() {
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

    @Test
    fun testStoreBook_producesSameUriAsGetBooks() {
        // Given
        val tmpFile = kotlin.io.path.createTempFile().toFile()
        val folderName = "A folder"
        val fileName = "A book.org"
        writeFileToRepo("...", "$folderName/$fileName")
        // When
        val gottenBook = syncRepo.books[0]
        MiscUtils.writeStringToFile("......", tmpFile) // N.B. Different content to ensure the repo file is actually changed
        val storedRook = syncRepo.storeBook(tmpFile, "$folderName/$fileName")
        tmpFile.delete()
        // Then
        assertEquals(gottenBook.uri, storedRook.uri)
    }

    @Test
    fun testStoreBook_inSubfolder() {
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
                val serverRepoDir = repoManipulationPoint as File
                val subFolder = File(serverRepoDir, "A folder")
                Assert.assertTrue(subFolder.exists())
                val bookFile = File(subFolder, "A book.org")
                Assert.assertTrue(bookFile.exists())
                assertEquals(testBookContent, bookFile.readText())
            }
            is GitRepo -> {
                val workingClone = repoManipulationPoint as File
                val git = Git(
                    FileRepositoryBuilder()
                        .addCeilingDirectory(workingClone)
                        .findGitDir(workingClone)
                        .build()
                )
                git.pull().call()
                val subFolder = File(workingClone, "A folder")
                Assert.assertTrue(subFolder.exists())
                val bookFile = File(subFolder, "A book.org")
                Assert.assertTrue(bookFile.exists())
                assertEquals(testBookContent, bookFile.readText())
            }
            is DocumentRepo -> {
                val repoDocFile = repoManipulationPoint as DocumentFile
                val subFolder = repoDocFile.findFile("A folder")
                Assert.assertTrue(subFolder!!.exists())
                Assert.assertTrue(subFolder.isDirectory)
                val bookFile = subFolder.findFile("A book.org")
                Assert.assertTrue(bookFile!!.exists())
                assertEquals(testBookContent, MiscUtils.readStringFromDocumentFile(bookFile))
            }
            is DropboxRepo -> {
                // Not really much to assert here; we don't really care how Dropbox implements things,
                // as long as URLs work as expected.
                val client = repoManipulationPoint as DropboxClient
                val retrievedFile = kotlin.io.path.createTempFile().toFile()
                client.download(syncRepo.uri, repositoryPath, retrievedFile)
                assertEquals(testBookContent, retrievedFile.readText())
            }
        }
    }

    @Test
    fun testRenameBook_expectedUri() {
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
            is GitRepo -> "Renamed%20book.org"
            is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "Renamed%20book.org"
            else -> syncRepo.uri.toString() + "/Renamed%20book.org"
        }
        assertEquals(expectedRookUri, renamedVrook.uri.toString())
    }

    @Test
    @Throws(IOException::class)
    fun testRenameBook_repoFileAlreadyExists() {
        // Given
        for (fileName in arrayOf("Original.org", "Renamed.org")) {
            writeFileToRepo("...", fileName)
        }
        val retrievedBookFile = kotlin.io.path.createTempFile().toFile()
        // When
        val originalRook = syncRepo.retrieveBook("Original.org", retrievedBookFile)
        assertThrows(IOException::class.java) { syncRepo.renameBook(originalRook.uri, "Renamed") }
        retrievedBookFile.delete()
    }

    @Test
    fun testRenameBook_fromRootToSubfolder() {
        // Given
        val tmpFile = kotlin.io.path.createTempFile().toFile()
        MiscUtils.writeStringToFile("...", tmpFile)
        // When
        val originalRook = syncRepo.storeBook(tmpFile, "Original book.org")
        tmpFile.delete()
        val renamedRook = syncRepo.renameBook(originalRook.uri, "A folder/Renamed book")
        // Then
        val expectedRookUri = when (syncRepo) {
            is GitRepo -> "A%20folder/Renamed%20book.org"
            is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "A%20folder%2FRenamed%20book.org"
            else -> syncRepo.uri.toString() + "/A%20folder/Renamed%20book.org"
        }
        assertEquals(expectedRookUri, renamedRook.uri.toString())
    }

    @Test
    fun testRenameBook_fromSubfolderToRoot() {
        // Given
        val tmpFile = kotlin.io.path.createTempFile().toFile()
        MiscUtils.writeStringToFile("...", tmpFile)
        // When
        val originalRook = syncRepo.storeBook(tmpFile, "A folder/Original book.org")
        tmpFile.delete()
        val renamedRook = syncRepo.renameBook(originalRook.uri, "Renamed book")
        // Then
        val expectedRookUri = when (syncRepo) {
            is GitRepo -> "Renamed%20book.org"
            is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "Renamed%20book.org"
            else -> syncRepo.uri.toString() + "/Renamed%20book.org"
        }
        assertEquals(expectedRookUri, renamedRook.uri.toString())
    }

    @Test
    fun testRenameBook_newSubfolderSameLeafName() {
        // Given
        val tmpFile = kotlin.io.path.createTempFile().toFile()
        MiscUtils.writeStringToFile("...", tmpFile)
        // When
        val originalRook = syncRepo.storeBook(tmpFile, "Old folder/Original book.org")
        tmpFile.delete()
        val renamedRook = syncRepo.renameBook(originalRook.uri, "New folder/Original book")
        // Then
        val expectedRookUri = when (syncRepo) {
            is GitRepo -> "New%20folder/Original%20book.org"
            is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "New%20folder%2FOriginal%20book.org"
            else -> syncRepo.uri.toString() + "/New%20folder/Original%20book.org"
        }
        assertEquals(expectedRookUri, renamedRook.uri.toString())
    }

    @Test
    fun testRenameBook_newSubfolderAndLeafName() {
        // Given
        val tmpFile = kotlin.io.path.createTempFile().toFile()
        MiscUtils.writeStringToFile("...", tmpFile)
        // When
        val originalRook = syncRepo.storeBook(tmpFile, "old folder/Original book.org")
        tmpFile.delete()
        val renamedRook = syncRepo.renameBook(originalRook.uri, "new folder/New book")
        // Then
        val expectedRookUri = when (syncRepo) {
            is GitRepo -> "new%20folder/New%20book.org"
            is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "new%20folder%2FNew%20book.org"
            else -> syncRepo.uri.toString() + "/new%20folder/New%20book.org"
        }
        assertEquals(expectedRookUri, renamedRook.uri.toString())
    }

    @Test
    fun testRenameBook_sameSubfolderNewLeafName() {
        // Given
        val tmpFile = kotlin.io.path.createTempFile().toFile()
        MiscUtils.writeStringToFile("...", tmpFile)
        // When
        val originalRook = syncRepo.storeBook(tmpFile, "old folder/Original book.org")
        tmpFile.delete()
        val renamedRook = syncRepo.renameBook(originalRook.uri, "old folder/New book")
        // Then
        val expectedRookUri = when (syncRepo) {
            is GitRepo -> "old%20folder/New%20book.org"
            is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "old%20folder%2FNew%20book.org"
            else -> syncRepo.uri.toString() + "/old%20folder/New%20book.org"
        }
        assertEquals(expectedRookUri, renamedRook.uri.toString())
    }

    companion object {

        const val repoDirName = "orgzly-android-test"
        var treeDocumentFileExtraSegment = if (Build.VERSION.SDK_INT < 30) {
            "/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2F$repoDirName%2F"
        } else {
            "/document/primary%3A$repoDirName%2F"
        }
    }
}
