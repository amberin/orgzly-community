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
import com.orgzly.android.util.MiscUtils
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert
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
        gitFileSynchronizer.mergeWithRemote()
        return expectedRookUri.toString()
    }
}
