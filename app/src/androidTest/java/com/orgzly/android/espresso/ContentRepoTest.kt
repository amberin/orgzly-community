package com.orgzly.android.espresso

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
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
import com.orgzly.android.espresso.util.EspressoUtils.contextualToolbarOverflowMenu
import com.orgzly.android.espresso.util.EspressoUtils.onBook
import com.orgzly.android.espresso.util.EspressoUtils.onNoteInBook
import com.orgzly.android.espresso.util.EspressoUtils.sync
import com.orgzly.android.espresso.util.EspressoUtils.waitId
import com.orgzly.android.repos.ContentRepo
import com.orgzly.android.repos.RepoIgnoreNode
import com.orgzly.android.repos.RepoType
import com.orgzly.android.sync.BookSyncStatus
import com.orgzly.android.ui.main.MainActivity
import com.orgzly.android.ui.repos.ReposActivity
import com.orgzly.android.util.MiscUtils
import org.hamcrest.CoreMatchers.endsWith
import org.hamcrest.core.AllOf.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.io.File
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
        if (this::treeDocumentFileUrl.isInitialized) {
            DocumentFile.fromTreeUri(context, Uri.parse(treeDocumentFileUrl))!!.delete()
        }
    }

    @Rule
    @JvmField
    var exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    @Throws(FileNotFoundException::class)
    fun testSyncWithDirectoryContainingPercent() {
        repoDirName = "space separated"
        setupContentRepo()
        writeStringToRepoFile("Notebook content 1", "notebook.org")
        testUtils.sync()
        assertEquals(1, dataRepository.getBooks().size.toLong())
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3Aspace%20separated", syncRepo.uri.toString())
    }

    @Test
    fun testRenameBookFromRootToSubfolder() {
        setupContentRepo()
        testUtils.setupBook("booky", "")
        testUtils.sync()
        dataRepository.renameBook(dataRepository.getBookView("booky")!!, "a/b")
        assertTrue(dataRepository.getBookView("a/b")!!.book.lastAction!!.message.contains("Renamed from "))
        testUtils.sync()
        assertEquals(dataRepository.getBook("a/b")!!.syncStatus, BookSyncStatus.NO_CHANGE.toString())
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3Aorgzly-local-dir-repo-test/document/primary%3Aorgzly-local-dir-repo-test%2Fa%2Fb.org",
            syncRepo.books[0].uri.toString()
        )
        assertEquals(1, dataRepository.getBooks().size.toLong())
    }

    @Test
    fun testRenameBookFromSubfolderToRoot() {
        setupContentRepo()
        testUtils.setupBook("a/b", "")
        testUtils.sync()
        dataRepository.renameBook(dataRepository.getBookView("a/b")!!, "booky")
        assertTrue(dataRepository.getBookView("booky")!!.book.lastAction!!.message.contains("Renamed from "))
        testUtils.sync()
        assertEquals(dataRepository.getBook("booky")!!.syncStatus, BookSyncStatus.NO_CHANGE.toString())
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3Aorgzly-local-dir-repo-test/document/primary%3Aorgzly-local-dir-repo-test%2Fbooky.org",
            syncRepo.books[0].uri.toString()
        )
        assertEquals(1, dataRepository.getBooks().size.toLong())
    }

    @Test
    fun testRenameBookNewSubfolderSameLeafName() {
        setupContentRepo()
        testUtils.setupBook("a/b", "")
        testUtils.sync()
        dataRepository.renameBook(dataRepository.getBookView("a/b")!!, "b/b")
        assertTrue(dataRepository.getBookView("b/b")!!.book.lastAction!!.message.contains("Renamed from "))
        testUtils.sync()
        assertEquals(dataRepository.getBook("b/b")!!.syncStatus, BookSyncStatus.NO_CHANGE.toString())
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3Aorgzly-local-dir-repo-test/document/primary%3Aorgzly-local-dir-repo-test%2Fb%2Fb.org",
            syncRepo.books[0].uri.toString()
        )
        assertEquals(1, dataRepository.getBooks().size.toLong())
    }

    @Test
    fun testRenameBookNewSubfolderAndLeafName() {
        setupContentRepo()
        testUtils.setupBook("a/b", "")
        testUtils.sync()
        dataRepository.renameBook(dataRepository.getBookView("a/b")!!, "b/c")
        assertTrue(dataRepository.getBookView("b/c")!!.book.lastAction!!.message.contains("Renamed from "))
        testUtils.sync()
        assertEquals(dataRepository.getBook("b/c")!!.syncStatus, BookSyncStatus.NO_CHANGE.toString())
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3Aorgzly-local-dir-repo-test/document/primary%3Aorgzly-local-dir-repo-test%2Fb%2Fc.org",
            syncRepo.books[0].uri.toString()
        )
        assertEquals(1, dataRepository.getBooks().size.toLong())
    }

    @Test
    fun testRenameBookSameSubfolderNewLeafName() {
        setupContentRepo()
        testUtils.setupBook("a/b", "")
        testUtils.sync()
        dataRepository.renameBook(dataRepository.getBookView("a/b")!!, "a/c")
        assertTrue(dataRepository.getBookView("a/c")!!.book.lastAction!!.message.contains("Renamed from "))
        testUtils.sync()
        assertEquals(dataRepository.getBook("a/c")!!.syncStatus, BookSyncStatus.NO_CHANGE.toString())
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3Aorgzly-local-dir-repo-test/document/primary%3Aorgzly-local-dir-repo-test%2Fa%2Fc.org",
            syncRepo.books[0].uri.toString()
        )
        assertEquals(1, dataRepository.getBooks().size.toLong())
    }

    private fun writeStringToRepoFile(content: String, fileName: String) {
        val tmpFile = File.createTempFile("abc", null)
        MiscUtils.writeStringToFile(content, tmpFile)
        syncRepo.storeBook(tmpFile, fileName)
        tmpFile.delete()
    }

    /**
     * An activity is required when creating this type of repo, because of the way Android handles
     * access permissions to content:// URLs.
     * @throws UiObjectNotFoundException
     */
    @Throws(UiObjectNotFoundException::class)
    private fun setupContentRepo() {
        addContentRepoInUi(repoDirName)
        repo = dataRepository.getRepos()[0]
        repoUri = Uri.parse(repo.url)
        syncRepo = testUtils.repoInstance(RepoType.DOCUMENT, repo.url, repo.id) as ContentRepo
        encodedRepoDirName = Uri.encode(repoDirName)
        documentTreeSegment = "/document/primary%3A$encodedRepoDirName%2F"
        treeDocumentFileUrl = "content://com.android.externalstorage.documents/tree/primary%3A$encodedRepoDirName"
        assertEquals(treeDocumentFileUrl, repo.url)
    }

    companion object {
        fun addContentRepoInUi(repoDirName: String) {
            ActivityScenario.launch(ReposActivity::class.java).use {
                onView(withId(R.id.activity_repos_directory)).perform(click())
                onView(withId(R.id.activity_repo_directory_browse_button))
                    .perform(click())
                SystemClock.sleep(200)
                // In Android file browser (Espresso cannot be used):
                val mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                mDevice.findObject(UiSelector().text("CREATE NEW FOLDER")).click()
                SystemClock.sleep(100)
                mDevice.findObject(UiSelector().text("Folder name")).text = repoDirName
                mDevice.findObject(UiSelector().text("OK")).click()
                mDevice.findObject(UiSelector().text("USE THIS FOLDER")).click()
                mDevice.findObject(UiSelector().text("ALLOW")).click()
                // Back in Orgzly:
                SystemClock.sleep(200)
                onView(isRoot()).perform(waitId(R.id.fab, 5000))
                onView(allOf(withId(R.id.fab), isDisplayed())).perform(click())
            }
        }
    }
}