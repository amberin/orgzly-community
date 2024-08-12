package com.orgzly.android.repos

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.git.GitFileSynchronizer
import com.orgzly.android.git.GitPreferencesFromRepoPrefs
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.prefs.RepoPreferences
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(AndroidJUnit4::class)
class GitRepoTest : SyncRepoTest {

    private lateinit var gitWorkingTree: File
    private lateinit var bareRepoDir: File
    private lateinit var gitFileSynchronizer: GitFileSynchronizer
    private lateinit var syncRepo: SyncRepo
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        bareRepoDir = createTempDirectory().toFile()
        Git.init().setBare(true).setDirectory(bareRepoDir).call()
        AppPreferences.gitIsEnabled(context, true)
        val repo = Repo(0, RepoType.GIT, "file://$bareRepoDir")
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
        bareRepoDir.deleteRecursively()
    }

    @Test
    override fun testGetBooks_singleOrgFile() {
        SyncRepoTest.testGetBooks_singleOrgFile(gitWorkingTree, syncRepo)
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
}