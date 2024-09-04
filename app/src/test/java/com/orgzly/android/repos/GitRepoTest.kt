package com.orgzly.android.repos

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orgzly.android.LocalStorage
import com.orgzly.android.TestUtils
import com.orgzly.android.data.DataRepository
import com.orgzly.android.data.DbRepoBookRepository
import com.orgzly.android.db.OrgzlyDatabase
import com.orgzly.android.db.entity.BookAction
import com.orgzly.android.git.GitFileSynchronizer
import com.orgzly.android.git.GitPreferencesFromRepoPrefs
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.prefs.RepoPreferences
import com.orgzly.android.sync.BookSyncStatus
import com.orgzly.android.util.MiscUtils
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

@RunWith(AndroidJUnit4::class)
class GitRepoTest : SyncRepoTest {

    private lateinit var workingCloneDir: File
    private lateinit var workingClone: Git
    private lateinit var bareRepoDir: File
    private lateinit var gitFileSynchronizer: GitFileSynchronizer
    private lateinit var dataRepository: DataRepository
    private lateinit var testUtils: TestUtils
    private lateinit var gitPreferences: GitPreferencesFromRepoPrefs
    private lateinit var mSyncRepo: SyncRepo

    @Before
    fun setup() {
        // Setup TestUtils
        val context: Context = ApplicationProvider.getApplicationContext()
        val database = OrgzlyDatabase.forFile(context, OrgzlyDatabase.NAME_FOR_TESTS)
        val dbRepoBookRepository = DbRepoBookRepository(database)
        val localStorage = LocalStorage(context)
        val repoFactory = RepoFactory(context, dbRepoBookRepository)
        dataRepository = DataRepository(
            context, database, repoFactory, context.resources, localStorage
        )
        testUtils = TestUtils(dataRepository, dbRepoBookRepository)
        dataRepository.clearDatabase()
        // Setup repo
        bareRepoDir = createTempDirectory().toFile()
        Git.init().setBare(true).setDirectory(bareRepoDir).call()
        AppPreferences.gitIsEnabled(context, true)
        val repo = testUtils.setupRepo(RepoType.GIT, "file://$bareRepoDir")
        val repoPreferences = RepoPreferences(context, repo.id, repo.url.toUri())
        gitPreferences = GitPreferencesFromRepoPrefs(repoPreferences)
        val orgzlyCloneDir = File(gitPreferences.repositoryFilepath())
        orgzlyCloneDir.mkdirs()
        val orgzlyRepoClone = GitRepo.ensureRepositoryExists(gitPreferences, true, null)
        gitFileSynchronizer = GitFileSynchronizer(orgzlyRepoClone, gitPreferences)
        val repoPropsMap = HashMap<String, String>()
        val repoWithProps = RepoWithProps(repo, repoPropsMap)
        mSyncRepo = GitRepo.getInstance(repoWithProps, context)
        // Setup working clone
        workingCloneDir = Files.createTempDirectory("orgzlytest").toFile()
        workingClone = CloneCommand().setURI(syncRepo.uri.toString()).setDirectory(workingCloneDir).call()
    }

    @After
    fun tearDown() {
        if (this::workingCloneDir.isInitialized)
            workingCloneDir.deleteRecursively()
        bareRepoDir.deleteRecursively()
    }

    override var syncRepo: SyncRepo
        get() = mSyncRepo
        set(value) {}
    override val repoManipulationPoint: Any
        get() = workingCloneDir

    override fun writeFileToRepo(content: String, repoRelativePath: String): String {
        val expectedRookUri = Uri.parse(Uri.encode(repoRelativePath, "/"))
        val targetFile = File(workingCloneDir, repoRelativePath)
        targetFile.parentFile!!.mkdirs()
        MiscUtils.writeStringToFile(content, targetFile)
        workingClone.add().addFilepattern(".").call()
        workingClone.commit().setMessage("").call()
        workingClone.push().call()
        // Ensure Orgzly's working tree is updated. This is needed for testing getBooks(), which
        // does not update the worktree on its own.
        gitPreferences.createTransportSetter().use { transportSetter ->
            gitFileSynchronizer.pull(
                transportSetter
            )
        }
        return expectedRookUri.toString()
    }

    /**
     * Ensure we support syncing to a new, empty Git repo.
     * Also verifies that a book without a link is linked and synced, if possible.
     */
    @Test
    fun testSyncRepo_repoHasNoCommits() {
        testUtils.setupBook("A Book", "...")
        syncRepo.syncRepo(dataRepository)
        val bookView = dataRepository.getBooks()[0]
        assertEquals(syncRepo.uri.toString(), bookView.linkRepo!!.url)
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
        writeFileToRepo("...", "Book one.org") // N.B. This helper method also performs "git pull" in the working tree
        syncRepo.syncRepo(dataRepository)
        assertEquals(1, dataRepository.getBooks().size)
    }

    @Test
    fun testSyncRepo_fileWasDeletedOnRemote() {
        // Ensure we have a synced book
        testUtils.setupBook("Book one", "...")
        syncRepo.syncRepo(dataRepository)
        var bookView = dataRepository.getBooks()[0]
        assertEquals(true, bookView.hasSync())
        assertEquals(mSyncRepo.uri.toString(), dataRepository.getBooks()[0].linkRepo!!.url)
        // Delete the file in our separate repo clone and push the change
        workingClone.pull().call()
        workingClone.rm().addFilepattern("Book one.org").setCached(false).call()
        workingClone.commit().setMessage("").call()
        workingClone.push().call()
        // Sync and verify status
        syncRepo.syncRepo(dataRepository)
        bookView = dataRepository.getBooks()[0]
        assertEquals(null, bookView.linkRepo)
        assertEquals(BookAction.Type.ERROR, bookView.book.lastAction!!.type)
        assertEquals(BookSyncStatus.ROOK_NO_LONGER_EXISTS.toString(), bookView.book.syncStatus)
        // hasSync should still return true, to help understand the book's state during the next sync
        assertEquals(true, bookView.hasSync())
        // Sync again and verify status
        syncRepo.syncRepo(dataRepository)
        bookView = dataRepository.getBooks()[0]
        assertEquals(null, bookView.linkRepo)
        assertEquals(BookAction.Type.ERROR, bookView.book.lastAction!!.type)
        assertEquals(BookSyncStatus.BOOK_WITH_PREVIOUS_ERROR_AND_NO_LINK.toString(), bookView.book.syncStatus)
    }
}