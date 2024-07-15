package com.orgzly.android.espresso

import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObjectNotFoundException
import androidx.test.uiautomator.UiSelector
import com.orgzly.R
import com.orgzly.android.BookName
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.espresso.util.EspressoUtils
import com.orgzly.android.espresso.util.EspressoUtils.contextualToolbarOverflowMenu
import com.orgzly.android.espresso.util.EspressoUtils.onBook
import com.orgzly.android.espresso.util.EspressoUtils.onNoteInBook
import com.orgzly.android.espresso.util.EspressoUtils.sync
import com.orgzly.android.espresso.util.EspressoUtils.waitId
import com.orgzly.android.repos.ContentRepo
import com.orgzly.android.repos.RepoIgnoreNode
import com.orgzly.android.repos.RepoType
import com.orgzly.android.ui.main.MainActivity
import com.orgzly.android.ui.repos.ReposActivity
import com.orgzly.android.util.MiscUtils
import org.hamcrest.CoreMatchers.endsWith
import org.hamcrest.core.AllOf.allOf
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.io.FileNotFoundException
import java.io.IOException


class ContentRepoTest : OrgzlyTest() {

    private var repoDirName = "orgzly-local-dir-repo-test"
    private lateinit var encodedRepoDirName: String
    private lateinit var documentTreeSegment: String
    private lateinit var treeDocumentFileUrl: String
    private lateinit var repo: Repo
    private lateinit var syncRepo: ContentRepo
    private lateinit var repoUri: Uri

    @Before
    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
    }

    @After
    @Throws(Exception::class)
    override fun tearDown() {
        super.tearDown()
        DocumentFile.fromTreeUri(context, Uri.parse(treeDocumentFileUrl))?.delete()
    }

    @Rule
    @JvmField
    var exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    @Throws(IOException::class)
    fun testStoringFile() {
        setupContentRepo()
        val tmpFile = dataRepository.getTempBookFile()
        try {
            MiscUtils.writeStringToFile("...", tmpFile)
            syncRepo.storeBook(tmpFile, "booky.org")
        } finally {
            tmpFile.delete()
        }
        val books = syncRepo.books
        assertEquals(1, books.size.toLong())
        assertEquals("booky", BookName.getInstance(context, books[0]).name)
        assertEquals("booky.org", BookName.getInstance(context, books[0]).fileName)
        assertEquals(repo.url, books[0].repoUri.toString())
        assertEquals(repo.url + documentTreeSegment + "booky.org", books[0].uri.toString())
    }

    @Test
    @Throws(IOException::class)
    fun testExtension() {
        setupContentRepo()
        MiscUtils.writeStringToDocumentFile("Notebook content 1", "01.txt", repoUri)
        MiscUtils.writeStringToDocumentFile("Notebook content 2", "02.o", repoUri)
        MiscUtils.writeStringToDocumentFile("Notebook content 3", "03.org", repoUri)
        val books = syncRepo.books
        assertEquals(1, books.size.toLong())
        assertEquals("03", BookName.getInstance(context, books[0]).name)
        assertEquals("03.org", BookName.getInstance(context, books[0]).fileName)
        assertEquals(repo.id, books[0].repoId)
        assertEquals(repo.url, books[0].repoUri.toString())
        assertEquals(repo.url + documentTreeSegment + "03.org", books[0].uri.toString())
    }

    @Test
    fun testRenameBook() {
        setupContentRepo()
        testUtils.setupBook("booky", "")
        testUtils.sync()
        var bookView: BookView? = dataRepository.getBookView("booky")
        assertEquals(repo.url, bookView!!.linkRepo!!.url)
        assertEquals(repo.url, bookView.syncedTo!!.repoUri.toString())
        assertEquals(
            repo.url + documentTreeSegment + "booky.org",
            bookView.syncedTo!!.uri.toString()
        )
        dataRepository.renameBook(bookView, "booky-renamed")
        bookView = dataRepository.getBookView("booky-renamed")
        assertEquals(repo.url, bookView!!.linkRepo!!.url)
        assertEquals(repo.url, bookView.syncedTo!!.repoUri.toString())
        assertEquals(
            repo.url + documentTreeSegment + "booky-renamed.org",
            bookView.syncedTo!!.uri.toString()
        )
    }

    @Test
    @Throws(FileNotFoundException::class)
    fun testSyncWithDirectoryContainingPercent() {
        repoDirName = "space separated"
        setupContentRepo()
        MiscUtils.writeStringToDocumentFile("Notebook content 1", "notebook.org", repoUri)
        testUtils.sync()
        assertEquals(1, dataRepository.getBooks().size.toLong())
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3Aspace%20separated", syncRepo.uri.toString())
    }

    @Test
    @Throws(IOException::class)
    fun testIgnoreRulePreventsLoadingBook() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        setupContentRepo()

        // Add .org files
        MiscUtils.writeStringToDocumentFile("content", "file1.org", repoUri)
        MiscUtils.writeStringToDocumentFile("content", "file2.org", repoUri)
        MiscUtils.writeStringToDocumentFile("content", "file3.org", repoUri)

        // Add .orgzlyignore file
        MiscUtils.writeStringToDocumentFile("*1.org\nfile3*", RepoIgnoreNode.IGNORE_FILE, repoUri)

        val books = syncRepo.books
        assertEquals(1, books.size.toLong())
        assertEquals("file2", BookName.getInstance(context, books[0]).name)
        assertEquals(repo.url + documentTreeSegment + "file2.org", books[0].uri.toString())
    }

    @Test
    fun testIgnoreRulePreventsRenamingBook() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        setupContentRepo()

        // Add .orgzlyignore file
        MiscUtils.writeStringToDocumentFile("file3*", RepoIgnoreNode.IGNORE_FILE, repoUri)
        // Create book and sync it
        testUtils.setupBook("booky", "")
        testUtils.sync()
        ActivityScenario.launch(MainActivity::class.java).use {
            // Rename to allowed name
            onBook(0).perform(ViewActions.longClick())
            contextualToolbarOverflowMenu().perform(click())
            onView(withText(R.string.rename)).perform(click())
            onView(withId(R.id.name)).perform(*EspressoUtils.replaceTextCloseKeyboard("file1"))
            onView(withText(R.string.rename)).perform(click())
            onBook(0, R.id.item_book_last_action).check(
                matches(withText(endsWith("Renamed from “booky”")))
            )
            // Rename to ignored name
            onBook(0).perform(ViewActions.longClick())
            contextualToolbarOverflowMenu().perform(click())
            onView(withText(R.string.rename)).perform(click())
            onView(withId(R.id.name)).perform(*EspressoUtils.replaceTextCloseKeyboard("file3"))
            onView(withText(R.string.rename)).perform(click())
            onBook(0, R.id.item_book_last_action).check(
                matches(withText(endsWith(context.getString(
                    R.string.error_file_matches_repo_ignore_rule,
                    RepoIgnoreNode.IGNORE_FILE)))))
        }
    }

    @Test
    @Throws(java.lang.Exception::class)
    fun testIgnoreRulePreventsLinkingBook() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        setupContentRepo()
        // Add .orgzlyignore file
        MiscUtils.writeStringToDocumentFile("*.org", RepoIgnoreNode.IGNORE_FILE, repoUri)
        testUtils.setupBook("booky", "")
        exceptionRule.expect(IOException::class.java)
        exceptionRule.expectMessage("matches a rule in .orgzlyignore")
        testUtils.syncOrThrow()
    }

    @Test
    fun testLoadNotebookFromSubfolder() {
        setupContentRepo()
        // Create subfolder
        val subfolder = DocumentFile.fromTreeUri(context, repoUri)?.createDirectory("a folder")
        // Write org file to subfolder
        MiscUtils.writeStringToDocumentFile("content", "a book.org", subfolder?.uri)

        testUtils.sync()

        val books = dataRepository.getBooks()
        assertEquals(1, books.size.toLong())
        assertEquals("a folder/a book", books[0].book.name)
        assertEquals(repo.url + documentTreeSegment + "a%20folder%2Fa%20book.org", books[0].syncedTo?.uri.toString())
    }

    @Test
    fun testIgnoreFileInSubfolder() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        setupContentRepo()
        // Add .orgzlyignore file
        MiscUtils.writeStringToDocumentFile("subfolder1/book1.org", RepoIgnoreNode.IGNORE_FILE, repoUri)
        // Create subfolder
        val subfolder = DocumentFile.fromTreeUri(context, repoUri)?.createDirectory("subfolder1")
        // Write 2 org files to subfolder
        MiscUtils.writeStringToDocumentFile("content", "book1.org", subfolder?.uri)
        MiscUtils.writeStringToDocumentFile("content", "book2.org", subfolder?.uri)

        testUtils.sync()

        val books = dataRepository.getBooks()
        assertEquals(1, books.size.toLong())
        assertEquals("subfolder1/book2", books[0].book.name)
    }

    @Test
    fun testUnIgnoreSingleFileInSubfolder() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        setupContentRepo()
        // Add .orgzlyignore file
        MiscUtils.writeStringToDocumentFile("subfolder1/**\n!subfolder1/book2.org", RepoIgnoreNode.IGNORE_FILE, repoUri)
        // Create subfolder
        val subfolder = DocumentFile.fromTreeUri(context, repoUri)?.createDirectory("subfolder1")
        // Write 2 org files to subfolder
        MiscUtils.writeStringToDocumentFile("content", "book1.org", subfolder?.uri)
        MiscUtils.writeStringToDocumentFile("content", "book2.org", subfolder?.uri)

        testUtils.sync()

        val books = dataRepository.getBooks()
        assertEquals(1, books.size.toLong())
        assertEquals("subfolder1/book2", books[0].book.name)
    }

    @Test
    fun testUpdateBookInSubfolder() {
        setupContentRepo()
        // Create subfolder
        val subfolder = DocumentFile.fromTreeUri(context, repoUri)?.createDirectory("folder one")
        // Create org file in subfolder
        MiscUtils.writeStringToDocumentFile("* DONE Heading 1", "book one.org", subfolder?.uri)

        testUtils.sync()
        assertEquals(1, dataRepository.getBooks().size.toLong())
        
        ActivityScenario.launch(MainActivity::class.java).use {
            // Modify book
            onBook(0).perform(click())
            onNoteInBook(1).perform(longClick())
            onView(withId(R.id.toggle_state)).perform(click())
            pressBack()
            pressBack()
            sync()
            onBook(0, R.id.item_book_last_action).check(
                matches(withText(endsWith("Saved to content://com.android.externalstorage.documents/tree/primary%3A$repoDirName")))
            )
            // Delete notebook from Orgzly and reload it to verify that our change was successfully written
            onBook(0).perform(longClick())
            contextualToolbarOverflowMenu().perform(click())
            onView(withText(R.string.delete)).perform(click())
            onView(withText(R.string.delete)).perform(click())
        }

        testUtils.sync()
        assertEquals(1, dataRepository.getBooks().size.toLong())
        testUtils.assertBook("folder one/book one", "* TODO Heading 1\n")
    }

    /**
     * An activity is required when creating this type of repo, because of the way Android handles
     * access permissions to content:// URLs.
     * @throws UiObjectNotFoundException
     */
    @Throws(UiObjectNotFoundException::class)
    private fun setupContentRepo() {
        ActivityScenario.launch(ReposActivity::class.java).use {
            onView(withId(R.id.activity_repos_directory)).perform(click())
            onView(withId(R.id.activity_repo_directory_browse_button))
                .perform(click())
            // In Android file browser (Espresso cannot be used):
            val mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            mDevice.findObject(UiSelector().text("CREATE NEW FOLDER")).click()
            mDevice.findObject(UiSelector().text("Folder name")).text = repoDirName
            mDevice.findObject(UiSelector().text("OK")).click()
            mDevice.findObject(UiSelector().text("USE THIS FOLDER")).click()
            mDevice.findObject(UiSelector().text("ALLOW")).click()
            // Back in Orgzly:
            onView(isRoot()).perform(waitId(R.id.fab, 5000))
            onView(allOf(withId(R.id.fab), isDisplayed())).perform(click())
        }
        repo = dataRepository.getRepos()[0]
        repoUri = Uri.parse(repo.url)
        syncRepo = testUtils.repoInstance(RepoType.DOCUMENT, repo.url, repo.id) as ContentRepo
        encodedRepoDirName = Uri.encode(repoDirName)
        documentTreeSegment = "/document/primary%3A$encodedRepoDirName%2F"
        treeDocumentFileUrl = "content://com.android.externalstorage.documents/tree/primary%3A$encodedRepoDirName"
        assertEquals(treeDocumentFileUrl, repo.url)
    }
}