package com.orgzly.android.repos

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orgzly.android.LocalStorage
import com.orgzly.android.TestUtils
import com.orgzly.android.data.DataRepository
import com.orgzly.android.data.DbRepoBookRepository
import com.orgzly.android.db.OrgzlyDatabase
import com.orgzly.android.db.OrgzlyDatabase.Companion.forFile
import com.orgzly.android.db.entity.BookAction
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.git.GitFileSynchronizer
import com.orgzly.android.git.GitPreferencesFromRepoPrefs
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.prefs.RepoPreferences
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

@RunWith(AndroidJUnit4::class)
class GitRepoTest : SyncRepoTest {

    private lateinit var gitWorkingTree: File
    private lateinit var bareRepoDir: File
    private lateinit var gitFileSynchronizer: GitFileSynchronizer
    private lateinit var repo: Repo
    private lateinit var syncRepo: SyncRepo
    private var context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dataRepository: DataRepository
    private lateinit var testUtils: TestUtils

    @Before
    fun setup() {
        // Setup TestUtils
        val database = forFile(context, OrgzlyDatabase.NAME_FOR_TESTS)
        val dbRepoBookRepository = DbRepoBookRepository(database)
        val localStorage = LocalStorage(context)
        val repoFactory = RepoFactory(context, dbRepoBookRepository)
        dataRepository = DataRepository(
            context, database, repoFactory, context.resources, localStorage
        )
        dataRepository.clearDatabase()
        testUtils = TestUtils(dataRepository, dbRepoBookRepository)
        // Setup repo
        bareRepoDir = createTempDirectory().toFile()
        Git.init().setBare(true).setDirectory(bareRepoDir).call()
        AppPreferences.gitIsEnabled(context, true)
        repo = testUtils.setupRepo(RepoType.GIT, "file://$bareRepoDir")
        val repoPreferences = RepoPreferences(context, repo.id, repo.url.toUri())
        val gitPreferences = GitPreferencesFromRepoPrefs(repoPreferences)
        gitWorkingTree = File(gitPreferences.repositoryFilepath())
        gitWorkingTree.mkdirs()
        val git = GitRepo.ensureRepositoryExists(gitPreferences, true, null)
        gitFileSynchronizer = GitFileSynchronizer(git, gitPreferences)
        val repoPropsMap = HashMap<String, String>()
        val repoWithProps = RepoWithProps(repo, repoPropsMap)
        syncRepo = GitRepo.getInstance(repoWithProps, context)
    }

    @After
    fun tearDown() {
        gitWorkingTree.deleteRecursively()
        bareRepoDir.deleteRecursively()
    }

    /**
     * Ensure we support syncing to a new, empty Git repo.
     * Also tests that a book without a link is linked and synced, if possible.
     */
    @Test
    fun testSyncRepo_repoHasNoCommits() {
        testUtils.setupBook("A Book", "...")
        syncRepo.syncRepo(dataRepository)
        val bookView = dataRepository.getBooks()[0]
        assertEquals(repo.url, bookView.linkRepo!!.url)
        assertNotNull(bookView.syncedTo?.revision)
        assertTrue(bookView.book.lastAction!!.message.contains("Saved to "))
    }

    @Test
    fun testSyncRepo_bookWithoutLinkAndMultipleRepos() {
        // Add a second repo
        testUtils.setupRepo(RepoType.MOCK, "mock://repo")
        testUtils.setupBook("A Book", "...")

        syncRepo.syncRepo(dataRepository)

        val bookView = dataRepository.getBooks()[0]
        assertEquals(BookAction.Type.ERROR, bookView.book.lastAction!!.type)
        assertTrue(bookView.book.lastAction!!.message.contains("multiple repositories"))
    }

    /**
     * The very first sync is special, since the remote head has not changed since cloning, but
     * we want to be sure that all books are loaded.
     */
    @Test
    fun testSyncRepo_firstSyncAfterCloning() {
        return // TODO
    }

    @Test
    override fun testGetBooks_singleOrgFile() {
        SyncRepoTest.testGetBooks_singleOrgFile(syncRepo)
    }

    @Test
    override fun testGetBooks_singleFileInSubfolder() {
        SyncRepoTest.testGetBooks_singleFileInSubfolder(gitWorkingTree, syncRepo)
    }

    @Test
    override fun testGetBooks_allFilesAreIgnored() {
        SyncRepoTest.testGetBooks_allFilesAreIgnored(gitWorkingTree, syncRepo)
    }

    @Test
    override fun testGetBooks_specificFileInSubfolderIsIgnored() {
        SyncRepoTest.testGetBooks_specificFileInSubfolderIsIgnored(gitWorkingTree, syncRepo)
    }

    @Test
    override fun testGetBooks_specificFileIsUnignored() {
        SyncRepoTest.testGetBooks_specificFileIsUnignored(gitWorkingTree, syncRepo)
    }

    @Test
    override fun testGetBooks_ignoredExtensions() {
        SyncRepoTest.testGetBooks_ignoredExtensions(syncRepo)
    }

    @Test
    override fun testStoreBook_expectedUri() {
        SyncRepoTest.testStoreBook_expectedUri(syncRepo)
    }

    @Test
    override fun testStoreBook_producesSameUriAsRetrieveBook() {
        SyncRepoTest.testStoreBook_producesSameUriAsRetrieveBook(syncRepo)
    }

    @Test
    override fun testStoreBook_producesSameUriAsGetBooks() {
        SyncRepoTest.testStoreBook_producesSameUriAsGetBooks(syncRepo)
    }

    @Test
    override fun testStoreBook_inSubfolder() {
        SyncRepoTest.testStoreBook_inSubfolder(gitWorkingTree, syncRepo)
    }

    @Test
    override fun testRenameBook_expectedUri() {
        SyncRepoTest.testRenameBook_expectedUri(syncRepo)
    }

    @Test(expected = IOException::class)
    override fun testRenameBook_repoFileAlreadyExists() {
        SyncRepoTest.testRenameBook_repoFileAlreadyExists(syncRepo)
    }

    @Test
    override fun testRenameBook_fromRootToSubfolder() {
        SyncRepoTest.testRenameBook_fromRootToSubfolder(syncRepo)
    }

    @Test
    override fun testRenameBook_fromSubfolderToRoot() {
        SyncRepoTest.testRenameBook_fromSubfolderToRoot(syncRepo)
    }

    @Test
    override fun testRenameBook_newSubfolderSameLeafName() {
        SyncRepoTest.testRenameBook_newSubfolderSameLeafName(syncRepo)
    }

    @Test
    override fun testRenameBook_newSubfolderAndLeafName() {
        SyncRepoTest.testRenameBook_newSubfolderAndLeafName(syncRepo)
    }

    @Test
    override fun testRenameBook_sameSubfolderNewLeafName() {
        SyncRepoTest.testRenameBook_sameSubfolderNewLeafName(syncRepo)
    }
}