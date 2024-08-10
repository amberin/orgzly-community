package com.orgzly.android.repos

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orgzly.android.BookName
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.repos.WebdavRepo.Companion.PASSWORD_PREF_KEY
import com.orgzly.android.repos.WebdavRepo.Companion.USERNAME_PREF_KEY
import com.orgzly.android.util.MiscUtils
import io.github.atetzner.webdav.server.MiltonWebDAVFileServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException


@RunWith(AndroidJUnit4::class)
class SyncRepoTest {

    private val serverUrl = "http://localhost:8081/"

    private lateinit var serverRootDir: File
    private lateinit var localServer: MiltonWebDAVFileServer
    private lateinit var syncRepo: SyncRepo
    private lateinit var tmpFile: File

    @Before
    fun setup() {
        serverRootDir = java.nio.file.Files.createTempDirectory("orgzly-webdav-test-").toFile()
        localServer = MiltonWebDAVFileServer(serverRootDir)
        localServer.userCredentials["user"] = "secret"
        localServer.start()
        val repo = Repo(0, RepoType.WEBDAV, serverUrl)
        val repoPropsMap = HashMap<String, String>()
        repoPropsMap[USERNAME_PREF_KEY] = "user"
        repoPropsMap[PASSWORD_PREF_KEY] = "secret"
        val repoWithProps = RepoWithProps(repo, repoPropsMap)
        syncRepo = WebdavRepo.getInstance(repoWithProps)
        assertEquals(serverUrl, repo.url)
        tmpFile = kotlin.io.path.createTempFile().toFile()
    }

    @After
    fun tearDown() {
        tmpFile.delete()
        if (this::localServer.isInitialized) {
            localServer.stop()
        }
        if (this::serverRootDir.isInitialized) {
            serverRootDir.deleteRecursively()
        }
    }

    @Test
    fun testGetBooks_singleOrgFile() {
        // N.B. Expected book name contains space
        val remoteBookFile = File(serverRootDir.absolutePath + "/book one.org")
        MiscUtils.writeStringToFile("...", remoteBookFile)
        val books = syncRepo.books
        assertEquals(1, books.size)
        assertEquals(serverUrl + "book%20one.org", books[0].uri.toString())
        val retrievedBookFile = kotlin.io.path.createTempFile().toFile()
        syncRepo.retrieveBook("book one.org", retrievedBookFile)
        // Assert that the two files are identical
        assertEquals(remoteBookFile.readText(), retrievedBookFile.readText())
        // Assert reported file name
        val rookFileName = BookName.getFileName(syncRepo.uri, books[0].uri)
        assertEquals("book one.org", rookFileName)
    }

    @Test
    fun testGetBooks_singleFileInSubfolder() {
        val subFolder = File(serverRootDir.absolutePath + "/folder")
        subFolder.mkdir()
        val remoteBookFile = File(subFolder.absolutePath + "/book one.org")
        MiscUtils.writeStringToFile("...", remoteBookFile)
        val books = syncRepo.books
        assertEquals(1, books.size)
        assertEquals(serverUrl + "folder/book%20one.org", books[0].uri.toString())
        // Assert reported file name
        val rookFileName = BookName.getFileName(syncRepo.uri, books[0].uri)
        assertEquals("folder/book one.org", rookFileName)
        // Assert that the two files are identical
        val retrievedBookFile = kotlin.io.path.createTempFile().toFile()
        syncRepo.retrieveBook(rookFileName, retrievedBookFile)
        assertEquals(remoteBookFile.readText(), retrievedBookFile.readText())
    }

    @Test
    fun testGetBooks_allFilesAreIgnored() {
        val subFolder = File(serverRootDir.absolutePath, "folder")
        subFolder.mkdir()
        val remoteBookFile = File(subFolder.absolutePath, "book one.org")
        MiscUtils.writeStringToFile("...", remoteBookFile)
        val ignoreFile = File(serverRootDir.absolutePath, RepoIgnoreNode.IGNORE_FILE)
        MiscUtils.writeStringToFile("*\n", ignoreFile)
        val books = syncRepo.books
        assertEquals(0, books.size)
    }

    @Test
    fun testGetBooks_specificFileInSubfolderIsIgnored() {
        val subFolder = File(serverRootDir.absolutePath, "folder")
        subFolder.mkdir()
        val remoteBookFile = File(subFolder.absolutePath, "book one.org")
        MiscUtils.writeStringToFile("...", remoteBookFile)
        val ignoreFile = File(serverRootDir.absolutePath, RepoIgnoreNode.IGNORE_FILE)
        MiscUtils.writeStringToFile("folder/book one.org\n", ignoreFile)
        val books = syncRepo.books
        assertEquals(0, books.size)
    }

    @Test
    fun testGetBooks_specificFileIsUnignored() {
        val subFolder = File(serverRootDir.absolutePath, "folder")
        subFolder.mkdir()
        val remoteBookFile = File(subFolder.absolutePath, "book one.org")
        MiscUtils.writeStringToFile("...", remoteBookFile)
        val ignoreFile = File(serverRootDir.absolutePath, RepoIgnoreNode.IGNORE_FILE)
        MiscUtils.writeStringToFile("*\n!folder/book one.org", ignoreFile)
        val books = syncRepo.books
        assertEquals(1, books.size)
    }

    @Test
    fun testGetBooks_ignoredExtensions() {
        for (fileName in arrayOf("file one.txt", "file two.o", "file three.org")) {
            val remoteBookFile = File(serverRootDir.absolutePath, fileName)
            MiscUtils.writeStringToFile("...", remoteBookFile)
        }
        val books = syncRepo.books
        assertEquals(1, books.size.toLong())
        assertEquals("file three", BookName.fromFileName(BookName.getFileName(syncRepo.uri, books[0].uri)).name)
    }

    @Test
    fun testStoreBook_expectedUri() {
        MiscUtils.writeStringToFile("...", tmpFile)
        val vrook = syncRepo.storeBook(tmpFile, "Book one.org")
        assertEquals(syncRepo.uri.toString() + "Book%20one.org", vrook.uri.toString())
    }

    @Test
    fun testStoreBook_producesSameUriAsRetrieveBook() {
        val repositoryPath = "a folder/a book.org"
        MiscUtils.writeStringToFile("...", tmpFile)
        val storedRook = syncRepo.storeBook(tmpFile, repositoryPath)
        val retrievedBook = syncRepo.retrieveBook(repositoryPath, tmpFile)
        assertEquals(retrievedBook.uri, storedRook.uri)
    }

    @Test
    fun testStoreBook_producesSameUriAsGetBooks() {
        val repositoryPath = "a folder/a book.org"
        val repoSubDir = File(serverRootDir.absolutePath, "a folder")
        repoSubDir.mkdir()
        val repoBookFile = File(repoSubDir, "a book.org")
        MiscUtils.writeStringToFile("...", repoBookFile)
        val getBook = syncRepo.books[0]
        MiscUtils.writeStringToFile(".......", tmpFile)
        val storedRook = syncRepo.storeBook(tmpFile, repositoryPath)
        assertEquals(getBook.uri, storedRook.uri)
    }

    @Test
    fun testStoreBook_inSubfolder() {
        MiscUtils.writeStringToFile("...", tmpFile)
        syncRepo.storeBook(tmpFile, "a folder/a book.org")
        val subFolder = File(serverRootDir, "a folder")
        assertTrue(subFolder.exists())
        val bookFile = File(subFolder, "a book.org")
        assertTrue(bookFile.exists())
        assertEquals("...", bookFile.readText())
    }

    @Test
    fun testRenameBook_expectedUri() {
        val remoteBookFile = File(serverRootDir.absolutePath + "/Book one.org")
        MiscUtils.writeStringToFile("...", remoteBookFile)
        val originalVrook = syncRepo.books[0]
        assertEquals(syncRepo.uri.toString() + "Book%20one.org", originalVrook.uri.toString())
        val renamedVrook = syncRepo.renameBook(originalVrook.uri, "Renamed book")
        assertEquals(syncRepo.uri.toString() + "Renamed%20book.org", renamedVrook.uri.toString())
    }

    @Test(expected = IOException::class)
    fun testRenameBook_repoFileAlreadyExists() {
        for (bookName in arrayOf("Original", "Renamed")) {
            val remoteBookFile = File(serverRootDir.absolutePath + "/" + bookName + ".org")
            MiscUtils.writeStringToFile("...", remoteBookFile)
        }
        val originalRook = syncRepo.retrieveBook("Original.org", tmpFile)
//        exceptionRule.expect(IOException::class.java)
//        exceptionRule.expectMessage("File at " + syncRepo.uri.toString() + "Renamed.org already exists")
        try {
            syncRepo.renameBook(originalRook.uri, "Renamed")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("File at " + syncRepo.uri.toString() + "Renamed.org already exists"))
            throw e
        }
    }

    @Test
    fun testRenameBook_fromRootToSubfolder() {
        MiscUtils.writeStringToFile("...", tmpFile)
        val originalRook = syncRepo.storeBook(tmpFile, "Original.org")
        val renamedRook = syncRepo.renameBook(originalRook.uri, "a folder/Renamed")
        assertEquals(syncRepo.uri.toString() + "a%20folder/Renamed.org", renamedRook.uri.toString())
    }

    @Test
    fun testRenameBook_fromSubfolderToRoot() {
        MiscUtils.writeStringToFile("...", tmpFile)
        val originalRook = syncRepo.storeBook(tmpFile, "a folder/Original.org")
        val renamedRook = syncRepo.renameBook(originalRook.uri, "Renamed")
        assertEquals(syncRepo.uri.toString() + "Renamed.org", renamedRook.uri.toString())
    }

    @Test
    fun testRenameBook_newSubfolderSameLeafName() {
        MiscUtils.writeStringToFile("...", tmpFile)
        val originalRook = syncRepo.storeBook(tmpFile, "old folder/Original.org")
        val renamedRook = syncRepo.renameBook(originalRook.uri, "new folder/Original")
        assertEquals(syncRepo.uri.toString() + "new%20folder/Original.org", renamedRook.uri.toString())
    }

    @Test
    fun testRenameBook_newSubfolderAndLeafName() {
        MiscUtils.writeStringToFile("...", tmpFile)
        val originalRook = syncRepo.storeBook(tmpFile, "old folder/Original book.org")
        val renamedRook = syncRepo.renameBook(originalRook.uri, "new folder/New book")
        assertEquals(syncRepo.uri.toString() + "new%20folder/New%20book.org", renamedRook.uri.toString())
    }

    @Test
    fun testRenameBook_sameSubfolderNewLeafName() {
        MiscUtils.writeStringToFile("...", tmpFile)
        val originalRook = syncRepo.storeBook(tmpFile, "old folder/Original book.org")
        val renamedRook = syncRepo.renameBook(originalRook.uri, "old folder/New book")
        assertEquals(syncRepo.uri.toString() + "old%20folder/New%20book.org", renamedRook.uri.toString())
    }
}