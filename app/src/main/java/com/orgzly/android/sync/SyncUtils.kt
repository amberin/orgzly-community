package com.orgzly.android.sync

import com.orgzly.BuildConfig
import com.orgzly.android.App
import com.orgzly.android.BookFormat
import com.orgzly.android.BookName
import com.orgzly.android.NotesOrgExporter
import com.orgzly.android.data.DataRepository
import com.orgzly.android.db.entity.BookAction
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.repos.GitRepo
import com.orgzly.android.repos.SyncRepo
import com.orgzly.android.repos.IntegrallySyncedRepo
import com.orgzly.android.repos.VersionedRook
import com.orgzly.android.util.LogUtils
import java.io.IOException

object SyncUtils {
    private val TAG: String = SyncUtils::class.java.name

    /**
     * Goes through each regular SyncRepo (i.e. not IntegrallySyncedRepos) and collects
     * all books from each one.
     */
    @Throws(IOException::class)
    @JvmStatic
    fun getBooksFromRegularSyncRepos(dataRepository: DataRepository): List<VersionedRook> {
        val result = ArrayList<VersionedRook>()

        val repoList = dataRepository.getRegularSyncRepos()

        for (repo in repoList) {
            val libBooks = repo.books
            /* Each book in repository. */
            result.addAll(libBooks)
        }
        return result
    }

    /**
     * Compares remote books with their local equivalent and calculates the syncStatus for each link.
     *
     * Acts only on regular SyncRepos, NOT on IntegrallySyncedRepos.
     *
     * @return number of links (unique book names)
     * @throws IOException
     */
    @Throws(IOException::class)
    @JvmStatic
    fun groupNotebooksByName(dataRepository: DataRepository): Map<String, BookNamesake> {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "Collecting local and remote books ...")

        val repos = dataRepository.getRepos()

        val localBooks = dataRepository.getBooks()
        val versionedRooks = getBooksFromRegularSyncRepos(dataRepository)

        /* Group local and remote books by name. */
        val namesakes = BookNamesake.getFromVrooks(
            App.getAppContext(), localBooks, versionedRooks)

        /* If there is no local book, create empty "dummy" one. */
        for (namesake in namesakes.values) {
            if (namesake.book == null) {
                namesake.book = dataRepository.createDummyBook(namesake.name)
            }

            namesake.updateStatus(repos.size)
        }

        return namesakes
    }

    /**
     * Passed [com.orgzly.android.sync.BookNamesake] is NOT updated after load or save.
     *
     * FIXME: Hardcoded BookName.Format.ORG below
     */
    @Throws(Exception::class)
    @JvmStatic
    fun syncNamesake(dataRepository: DataRepository, namesake: BookNamesake): BookAction {
        val repoEntity: Repo?
        val repoUrl: String
        val fileName: String
        var bookAction: BookAction? = null

        when (namesake.status!!) {
            BookSyncStatus.NO_CHANGE ->
                bookAction = BookAction.forNow(BookAction.Type.INFO, namesake.status.msg())

            BookSyncStatus.BOOK_WITHOUT_LINK_AND_ONE_OR_MORE_ROOKS_EXIST,
            BookSyncStatus.DUMMY_WITHOUT_LINK_AND_MULTIPLE_ROOKS,
            BookSyncStatus.NO_BOOK_MULTIPLE_ROOKS,
            BookSyncStatus.ONLY_BOOK_WITHOUT_LINK_AND_MULTIPLE_REPOS,
            BookSyncStatus.BOOK_WITH_LINK_AND_ROOK_EXISTS_BUT_LINK_POINTING_TO_DIFFERENT_ROOK,
            BookSyncStatus.CONFLICT_BOTH_BOOK_AND_ROOK_MODIFIED,
            BookSyncStatus.CONFLICT_BOOK_WITH_LINK_AND_ROOK_BUT_NEVER_SYNCED_BEFORE,
            BookSyncStatus.CONFLICT_LAST_SYNCED_ROOK_AND_LATEST_ROOK_ARE_DIFFERENT,
            BookSyncStatus.ROOK_AND_VROOK_HAVE_DIFFERENT_REPOS,
            BookSyncStatus.ONLY_DUMMY,
            BookSyncStatus.CONFLICT_STAYING_ON_TEMPORARY_BRANCH ->
                bookAction = BookAction.forNow(BookAction.Type.ERROR, namesake.status.msg())

            /* Load remote book. */

            BookSyncStatus.NO_BOOK_ONE_ROOK, BookSyncStatus.DUMMY_WITHOUT_LINK_AND_ONE_ROOK -> {
                dataRepository.loadBookFromRepo(namesake.rooks[0])
                bookAction = BookAction.forNow(
                    BookAction.Type.INFO,
                    namesake.status.msg(namesake.rooks[0].uri))
            }

            BookSyncStatus.DUMMY_WITH_LINK, BookSyncStatus.BOOK_WITH_LINK_AND_ROOK_MODIFIED -> {
                dataRepository.loadBookFromRepo(namesake.latestLinkedRook)
                bookAction = BookAction.forNow(
                    BookAction.Type.INFO,
                    namesake.status.msg(namesake.latestLinkedRook.uri))
            }

            /* Save local book to repository. */

            BookSyncStatus.ONLY_BOOK_WITHOUT_LINK_AND_ONE_REPO -> {
                repoEntity = dataRepository.getRepos().iterator().next()
                repoUrl = repoEntity.url
                fileName = BookName.fileName(namesake.book.book.name, BookFormat.ORG)
                dataRepository.saveBookToRepo(repoEntity, fileName, namesake.book, BookFormat.ORG)
                bookAction = BookAction.forNow(BookAction.Type.INFO, namesake.status.msg(repoUrl))
            }

            BookSyncStatus.BOOK_WITH_LINK_LOCAL_MODIFIED -> {
                repoEntity = namesake.book.linkRepo
                repoUrl = repoEntity!!.url
                fileName = BookName.getFileName(App.getAppContext(), namesake.book.syncedTo!!.uri)
                dataRepository.saveBookToRepo(repoEntity, fileName, namesake.book, BookFormat.ORG)
                bookAction = BookAction.forNow(BookAction.Type.INFO, namesake.status.msg(repoUrl))
            }

            BookSyncStatus.ONLY_BOOK_WITH_LINK -> {
                repoEntity = namesake.book.linkRepo
                repoUrl = repoEntity!!.url
                fileName = BookName.fileName(namesake.book.book.name, BookFormat.ORG)
                dataRepository.saveBookToRepo(repoEntity, fileName, namesake.book, BookFormat.ORG)
                bookAction = BookAction.forNow(BookAction.Type.INFO, namesake.status.msg(repoUrl))
            }
        }

        // if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "Syncing $namesake: $bookAction")

        return bookAction
    }
}