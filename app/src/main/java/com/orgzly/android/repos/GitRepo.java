package com.orgzly.android.repos;

import static java.nio.file.Files.createTempDirectory;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.orgzly.R;
import com.orgzly.android.App;
import com.orgzly.android.NotesOrgExporter;
import com.orgzly.android.BookFormat;
import com.orgzly.android.BookName;
import com.orgzly.android.data.DataRepository;
import com.orgzly.android.db.entity.Book;
import com.orgzly.android.db.entity.BookAction;
import com.orgzly.android.db.entity.BookView;
import com.orgzly.android.db.entity.Repo;
import com.orgzly.android.git.GitFileSynchronizer;
import com.orgzly.android.git.GitPreferences;
import com.orgzly.android.git.GitPreferencesFromRepoPrefs;
import com.orgzly.android.git.GitTransportSetter;
import com.orgzly.android.prefs.RepoPreferences;
import com.orgzly.android.sync.BookNamesake;
import com.orgzly.android.sync.BookSyncStatus;
import com.orgzly.android.sync.SyncState;
import com.orgzly.android.util.MiscUtils;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class GitRepo implements SyncRepo, TwoWaySyncRepo {
    private final static String TAG = GitRepo.class.getName();
    private final long repoId;
    private final RepoType repoType = RepoType.GIT;

    /**
     * Used as cause when we try to clone into a non-empty directory
     */
    public static class DirectoryNotEmpty extends Exception {
        public File dir;

        DirectoryNotEmpty(File dir) {
            this.dir = dir;
        }
    }

    public static GitRepo getInstance(RepoWithProps props, Context context) throws IOException {
        // TODO: This doesn't seem to be implemented in the same way as WebdavRepo.kt, do
        //  we want to store configuration data the same way they do?
        Repo repo = props.getRepo();
        Uri repoUri = Uri.parse(repo.getUrl());
        RepoPreferences repoPreferences = new RepoPreferences(context, repo.getId(), repoUri);
        GitPreferencesFromRepoPrefs prefs = new GitPreferencesFromRepoPrefs(repoPreferences);

        // TODO: Build from info

        return build(props.getRepo().getId(), prefs);
    }

    private static GitRepo build(long id, GitPreferences prefs) throws IOException {
        Git git = ensureRepositoryExists(prefs, false, null);

        StoredConfig config = git.getRepository().getConfig();
        config.setString("remote", prefs.remoteName(), "url", prefs.remoteUri().toString());
        config.setString("user", null, "name", prefs.getAuthor());
        config.setString("user", null, "email", prefs.getEmail());
        config.setString("gc", null, "auto", "256");
        config.save();

        return new GitRepo(id, git, prefs);
    }

    static boolean isRepo(FileRepositoryBuilder frb, File f) {
        frb.addCeilingDirectory(f).findGitDir(f);
        return frb.getGitDir() != null && frb.getGitDir().exists();
    }

    public static Git ensureRepositoryExists(
            GitPreferences prefs, boolean clone, ProgressMonitor pm) throws IOException {
        return ensureRepositoryExists(
                prefs.remoteUri(), new File(prefs.repositoryFilepath()),
                prefs.createTransportSetter(), clone, pm);
    }

    public static Git ensureRepositoryExists(
            Uri repoUri, File directoryFile, GitTransportSetter transportSetter,
            boolean clone, ProgressMonitor pm)
            throws IOException {
        if (clone) {
            return cloneRepo(repoUri, directoryFile, transportSetter, pm);
        } else {
            return verifyExistingRepo(directoryFile);
        }
    }

    /**
     * Check that the given path contains a valid git repository
     * @param directoryFile the path to check
     * @return A Git repo instance
     * @throws IOException Thrown when either the directory doesnt exist or is not a git repository
     */
    private static Git verifyExistingRepo(File directoryFile) throws IOException {
        if (!directoryFile.exists()) {
            throw new IOException(String.format("The directory %s does not exist", directoryFile), new FileNotFoundException());
        }

        FileRepositoryBuilder frb = new FileRepositoryBuilder();
        if (!isRepo(frb, directoryFile)) {
            throw new IOException(
                    String.format("Directory %s is not a git repository.",
                            directoryFile.getAbsolutePath()));
        }
        return new Git(frb.build());
    }

    /**
     * Attempts to clone a git repository
     * @param repoUri Remote location of git repository
     * @param directoryFile Location to clone to
     * @param transportSetter Transport information
     * @param pm Progress reporting helper
     * @return A Git repo instance
     * @throws IOException Thrown when directoryFile doesn't exist or isn't empty. Also thrown
     * when the clone fails
     */
    private static Git cloneRepo(Uri repoUri, File directoryFile, GitTransportSetter transportSetter,
                      ProgressMonitor pm) throws IOException {
        if (!directoryFile.exists()) {
            throw new IOException(String.format("The directory %s does not exist", directoryFile), new FileNotFoundException());
        }

        // Using list() can be resource intensive if there's many files, but since we just call it
        // at the time of cloning once we should be fine for now
        if (Objects.requireNonNull(directoryFile.list()).length != 0) {
            throw new IOException("The directory must be empty", new DirectoryNotEmpty(directoryFile));
        }

        try {
            CloneCommand cloneCommand = Git.cloneRepository().
                    setURI(repoUri.toString()).
                    setProgressMonitor(pm).
                    setDirectory(directoryFile);
            transportSetter.setTransport(cloneCommand);
            return cloneCommand.call();
        } catch (GitAPIException | JGitInternalException e) {
            try {
                FileUtils.delete(directoryFile, FileUtils.RECURSIVE);
                // This is done to show sensible error messages when trying to create a new git sync
                directoryFile.mkdirs();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            throw new IOException(e);
        }
    }

    private final Git git;
    private final GitFileSynchronizer synchronizer;
    private final GitPreferences preferences;

    public GitRepo(long id, Git g, GitPreferences prefs) {
        repoId = id;
        git = g;
        preferences = prefs;
        synchronizer = new GitFileSynchronizer(git, prefs);
    }

    public boolean isConnectionRequired() {
        return true;
    }

    @Override
    public boolean isAutoSyncSupported() {
        return true;
    }

    @Override
    public boolean isIntegrallySynced() {
        return true;
    }

    /**
     * N.B: NOT called during regular GitRepo syncing, only during force-loading.
     * @param file The contents of this file should be stored at the remote location/repo
     * @param repoRelativePath The contents ({@code file}) should be stored under this
     *                       (non-encoded) name
     * @return
     * @throws IOException
     */
    public VersionedRook storeBook(File file, String repoRelativePath) throws IOException {
        synchronizer.writeFileAndAddToIndex(file, repoRelativePath);
        synchronizer.commitAnyStagedChanges();
        try (GitTransportSetter transportSetter = preferences.createTransportSetter()) {
            synchronizer.push(transportSetter);
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            throw new RuntimeException(e);
        }
        return currentVersionedRook(Uri.EMPTY.buildUpon().path(repoRelativePath).build());
    }

    /**
     * N.B: NOT called during regular GitRepo syncing, only during force-loading.
     * @param repoRelativePath
     * @param destination
     * @return
     * @throws IOException
     */
    @Override
    public VersionedRook retrieveBook(String repoRelativePath, File destination) throws IOException {
        Uri sourceUri = Uri.EMPTY.buildUpon().path(repoRelativePath).build();
        try (GitTransportSetter transportSetter = preferences.createTransportSetter()) {
            synchronizer.fetch(transportSetter);
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            throw new RuntimeException(e);
        }
        try {
            // Reset our entire working tree to the remote head
            // TODO: Introduce helper method for loading remote changes which can be called both
            //  here and during regular syncing.
            synchronizer.hardResetToRemoteHead();
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
        synchronizer.retrieveLatestVersionOfFile(repoRelativePath, destination);
        return currentVersionedRook(sourceUri);
    }

    @Override
    public InputStream openRepoFileInputStream(String repoRelativePath) throws IOException {
        Uri sourceUri = Uri.EMPTY.buildUpon().path(repoRelativePath).build();
        return synchronizer.openRepoFileInputStream(sourceUri.getPath());
    }

    private VersionedRook currentVersionedRook(Uri uri) {
        RevCommit commit = null;
        try {
            commit = synchronizer.getLastCommitOfFile(uri);
        } catch (GitAPIException e) {
            e.printStackTrace();
        }
        assert commit != null;
        long mtime = (long)commit.getCommitTime()*1000;
        return new VersionedRook(repoId, repoType, getUri(), uri, commit.name(), mtime);
    }

    public List<VersionedRook> getBooks() throws IOException {
        List<VersionedRook> result = new ArrayList<>();
        if (synchronizer.currentHead() == null) {
            return result;
        }

        TreeWalk walk = new TreeWalk(git.getRepository());
        walk.reset();
        walk.setRecursive(true);
        walk.addTree(synchronizer.currentHead().getTree());
        final RepoIgnoreNode ignores = new RepoIgnoreNode(this);
        walk.setFilter(new TreeFilter() {
            @Override
            public boolean include(TreeWalk walker) {
                final FileMode mode = walk.getFileMode();
                final boolean isDirectory = mode == FileMode.TREE;
                final String repoRelativePath = walk.getPathString();
                if (ignores.isIgnored(repoRelativePath, isDirectory) == IgnoreNode.MatchResult.IGNORED)
                    return false;
                if (isDirectory)
                    return true;
                return BookName.isSupportedFormatFileName(repoRelativePath);
            }

            @Override
            public boolean shouldBeRecursive() {
                return true;
            }

            @Override
            public TreeFilter clone() {
                return this;
            }
        });
        while (walk.next()) {
            result.add(currentVersionedRook(Uri.EMPTY.buildUpon().path(walk.getPathString()).build()));
        }
        return result;
    }

    public Uri getUri() {
        return preferences.remoteUri();
    }

    public void delete(Uri uri) throws IOException {
        try (GitTransportSetter transportSetter = preferences.createTransportSetter()) {
            synchronizer.fetch(transportSetter);
            if (synchronizer.deleteFileFromRepo(uri, transportSetter))
                synchronizer.push(transportSetter);
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            throw new RuntimeException(e);
        }
    }

    public VersionedRook renameBook(Uri oldFullUri, String newName) throws IOException {
        String oldPath = oldFullUri.getPath();
        String newPath = BookName.repoRelativePath(newName, BookFormat.ORG);
        try (GitTransportSetter transportSetter = preferences.createTransportSetter()) {
            if (synchronizer.renameFileInRepo(oldPath, newPath, transportSetter)) {
                synchronizer.push(transportSetter);
                return currentVersionedRook(Uri.EMPTY.buildUpon().path(newPath).build());
            } else {
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            throw new IOException(e);
        }
    }

    @Nullable
    @Override
    public SyncState syncRepo(DataRepository dataRepository) throws Exception {
        RevCommit remoteHeadBeforeFetch = synchronizer.getRemoteHead();
        RevCommit newRemoteHead = null;
        List<Book> allLinkedBooks = dataRepository.getBooksLinkedToRepo(repoId);
        // If there are no books at all linked to the repo, make sure we
        // aren't missing anything (e.g. during first sync, right after cloning).
        if (allLinkedBooks.isEmpty()) {
            for (VersionedRook vrook : getBooks()) {
                BookView bookView = loadBook(dataRepository, vrook);
                storeBookStatus(dataRepository, bookView, BookSyncStatus.NO_BOOK_ONE_ROOK);
            }
        }
        // Create map of all books which need syncing. Add all which are out of sync or never
        // synced.
        Map<String, BookNamesake> syncedBooks = new HashMap<>();
        for (Book book : allLinkedBooks) {
            BookView bookView = dataRepository.getBookView(book.getId());
            assert bookView != null;
            if (bookView.isOutOfSync() || !bookView.hasSync()) {
                BookNamesake namesake = new BookNamesake(book.getName());
                namesake.setBook(dataRepository.getBookView(book.getId()));
                syncedBooks.put(book.getName(), namesake);
            }
        }
        // Link and add any orphan books, if possible
        for (BookView bookView : dataRepository.getBookViewsWithoutLink()) {
            if (dataRepository.getRepos().size() > 1) {
                storeBookStatus(dataRepository, bookView,
                        BookSyncStatus.ONLY_BOOK_WITHOUT_LINK_AND_MULTIPLE_REPOS);
            } else {
                BookAction lastAction = bookView.getBook().getLastAction();
                if (lastAction != null && lastAction.getType() == BookAction.Type.ERROR) {
                    storeBookStatus(dataRepository, bookView,
                            BookSyncStatus.BOOK_WITH_PREVIOUS_ERROR_AND_NO_LINK);
                } else {
                    dataRepository.setLink(bookView.getBook().getId(), new Repo(repoId, repoType,
                            getUri().toString()));
                    BookNamesake namesake = new BookNamesake(bookView.getBook().getName());
                    namesake.setBook(bookView);
                    syncedBooks.put(bookView.getBook().getName(), namesake);
                }
            }
        }
        // Export all syncable books and add to index
        for (BookNamesake namesake : syncedBooks.values()) {
            dataRepository.setBookLastActionAndSyncStatus(namesake.getBook().getBook().getId(),
                    BookAction.forNow(
                    BookAction.Type.PROGRESS,
                    App.getAppContext().getString(R.string.syncing_in_progress)));
            File tmpFile = dataRepository.getTempBookFile();
            try {
                new NotesOrgExporter(dataRepository).exportBook(namesake.getBook().getBook(), tmpFile);
                String repoRelativePath = BookName.getRepoRelativePath(namesake.getBook());
                synchronizer.writeFileAndAddToIndex(tmpFile, repoRelativePath);
            } catch (Exception e) {
                dataRepository.setBookLastActionAndSyncStatus(namesake.getBook().getBook().getId(),
                        BookAction.forNow(
                                BookAction.Type.ERROR,
                                App.getAppContext().getString(R.string.syncing_failed_title)));
                throw e;
            } finally {
                tmpFile.delete();
            }
            namesake.setStatus(BookSyncStatus.BOOK_WITH_LINK_LOCAL_MODIFIED);
        }
        boolean rebaseWasAttempted = false;
        // We know we must connect to the remote at some point, whether we have local changes or
        // not. Let's do it here.
        try (GitTransportSetter transportSetter = preferences.createTransportSetter()) {
            if (!syncedBooks.isEmpty()) {
                RevCommit newCommit = synchronizer.commitAnyStagedChanges();
                for (BookNamesake namesake : syncedBooks.values()) {
                    if (newCommit != null) {
                        Uri fileUri = Uri.EMPTY.buildUpon().path(BookName.getRepoRelativePath(namesake.getBook())).build();
                        VersionedRook vrook = new VersionedRook(repoId, repoType, getUri(), fileUri, newCommit.name(), (long) newCommit.getCommitTime() * 1000);
                        dataRepository.updateBookLinkAndSync(namesake.getBook().getBook().getId(), vrook);
                    }
                    dataRepository.setBookIsNotModified(namesake.getBook().getBook().getId());
                }
                // Try pushing
                RemoteRefUpdate pushResult = synchronizer.pushWithResult(transportSetter);
                if (pushResult == null) throw new IOException("Git push failed unexpectedly");
                switch (pushResult.getStatus()) {
                    case OK, UP_TO_DATE -> {
                        for (BookNamesake namesake : syncedBooks.values()) {
                            storeBookStatus(dataRepository, namesake.getBook(),
                                    namesake.getStatus());
                        }
                    }
                    case REJECTED_NONFASTFORWARD, REJECTED_REMOTE_CHANGED -> {
                        // Try rebasing on latest remote head
                        newRemoteHead = synchronizer.fetch(transportSetter);
                        switch (synchronizer.rebase().getStatus()) { // Only remote changes
                            case FAST_FORWARD, OK -> { // Remote and local changes
                                if (!syncedBooks.isEmpty()) {
                                    synchronizer.push(transportSetter);
                                    for (BookNamesake namesake : syncedBooks.values()) {
                                        storeBookStatus(dataRepository, namesake.getBook(),
                                                namesake.getStatus());
                                    }
                                }
                            }
                            default -> {
                                // Rebase failed; push to conflict branch
                                synchronizer.pushToConflictBranch(transportSetter);
                                for (BookNamesake namesake : syncedBooks.values()) {
                                    namesake.setStatus(BookSyncStatus.CONFLICT_SAVED_TO_TEMP_BRANCH);
                                    storeBookStatus(dataRepository, namesake.getBook(),
                                            namesake.getStatus());
                                }
                            }
                        }
                        rebaseWasAttempted = true;
                    }
                    default ->
                            throw new IOException("Error during git push: " + pushResult.getMessage());
                }
            } else {
                // No local changes, but fetch is needed to discover remote changes
                newRemoteHead = synchronizer.fetch(transportSetter);
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            throw new RuntimeException(e);
        } // Connection to remote is closed here; we have both pushed and fetched, if needed.
        if (newRemoteHead != null && !newRemoteHead.name().equals(remoteHeadBeforeFetch.name())) {
            // There are remote changes.
            // Ensure we have rebased on the remote head.
            if (!rebaseWasAttempted) {
                if (!synchronizer.rebase().getStatus().isSuccessful())
                    throw new IOException("Unexpectedly failed to merge with Git remote branch");
            }
            // Reload any changed books and update their statuses.
            List<DiffEntry> remoteChanges;
            remoteChanges = synchronizer.getCommitDiff(remoteHeadBeforeFetch, newRemoteHead);
            for (DiffEntry changedFile : remoteChanges) {
                BookSyncStatus status = null;
                BookView bookView = null;
                switch (changedFile.getChangeType()) {
                    case MODIFY -> {
                        BookNamesake alreadyChanged =
                                syncedBooks.get(BookName.fromRepoRelativePath(changedFile.getNewPath()).getName());
                        if (alreadyChanged != null) {
                            if (alreadyChanged.getStatus() == BookSyncStatus.CONFLICT_SAVED_TO_TEMP_BRANCH)
                                break;
                            // There were both local and remote changes, but no conflict
                            status = BookSyncStatus.CONFLICT_BOTH_BOOK_AND_ROOK_MODIFIED;
                            bookView = loadBook(dataRepository, changedFile.getNewPath());
                        } else {
                            // There were only remote changes. Add the book to list of synced books.
                            status = BookSyncStatus.BOOK_WITH_LINK_AND_ROOK_MODIFIED;
                            bookView = loadBook(dataRepository, changedFile.getNewPath());
                            BookNamesake namesake = new BookNamesake(bookView.getBook().getName());
                            namesake.setBook(bookView);
                            namesake.setStatus(status);
                            syncedBooks.put(namesake.getName(), namesake);
                        }
                    }
                    case ADD -> {
                        if (BookName.isSupportedFormatFileName(changedFile.getNewPath())) {
                            bookView = loadBook(dataRepository, changedFile.getNewPath());
                            status = BookSyncStatus.NO_BOOK_ONE_ROOK;
                        }
                    }
                    case DELETE -> {
                        String repoRelativePath = changedFile.getOldPath();
                        bookView =
                                dataRepository.getBookView(BookName.fromRepoRelativePath(repoRelativePath).getName());
                        assert bookView != null;
                        // Just remove the repo link and update book status; don't delete the book
                        dataRepository.setLink(bookView.getBook().getId(), null);
                        status = BookSyncStatus.ROOK_NO_LONGER_EXISTS;
                        // Avoid setting the NO_CHANGE status later
                        syncedBooks.put(bookView.getBook().getName(),
                                new BookNamesake(bookView.getBook().getName()));
                    }

                    // TODO: Handle RENAME, COPY
                    default ->
                            throw new IOException("Unsupported remote change in Git repo (file renamed or copied)");
                }
                if (status != null && bookView != null) {
                    storeBookStatus(dataRepository, bookView, status);
                }
            }
        }
        // Update the status of all untouched books.
        for (Book book : allLinkedBooks) {
            if (!syncedBooks.containsKey(book.getName())) {
                storeBookStatus(dataRepository, dataRepository.getBookView(book.getId()),
                        BookSyncStatus.NO_CHANGE);
            }
        }
        return null;
    }

    @Override
    public RepoType getType() {
        return repoType;
    }

    private BookView loadBook(DataRepository dataRepository, String repoRelativePath) throws IOException {
        BookView bookView;
        File tmpFile = dataRepository.getTempBookFile();
        try {
            synchronizer.retrieveLatestVersionOfFile(repoRelativePath, tmpFile);
            VersionedRook vrook =
                    currentVersionedRook(Uri.EMPTY.buildUpon().path(repoRelativePath).build());
            BookName bookName = BookName.fromRook(vrook);
            bookView = dataRepository.loadBookFromFile(
                    bookName.getName(),
                    bookName.getFormat(),
                    tmpFile,
                    vrook
            );
        } finally {
            tmpFile.delete();
        }
        return bookView;
    }

    private BookView loadBook(DataRepository dataRepository, VersionedRook vrook) throws IOException {
        BookView bookView;
        File tmpFile = dataRepository.getTempBookFile();
        try {
            synchronizer.retrieveLatestVersionOfFile(BookName.fromRook(vrook).getRepoRelativePath(), tmpFile);
            BookName bookName = BookName.fromRook(vrook);
            bookView = dataRepository.loadBookFromFile(
                    bookName.getName(),
                    bookName.getFormat(),
                    tmpFile,
                    vrook
            );
        } finally {
            tmpFile.delete();
        }
        return bookView;
    }

    private String currentBranch() throws IOException {
        return git.getRepository().getBranch();
    }

    private void storeBookStatus(DataRepository dataRepository,
                                 BookView bookView,
                                 BookSyncStatus status) throws IOException {
        BookAction.Type actionType = BookAction.Type.INFO;
        String actionMessageArgument = "";
        switch (status) {
            case ONLY_BOOK_WITHOUT_LINK_AND_ONE_REPO:
            case NO_BOOK_ONE_ROOK:
            case BOOK_WITH_LINK_LOCAL_MODIFIED:
            case BOOK_WITH_LINK_AND_ROOK_MODIFIED:
            case ONLY_BOOK_WITH_LINK:
                actionMessageArgument = String.format("branch \"%s\"", currentBranch());
                break;
            case ONLY_BOOK_WITHOUT_LINK_AND_MULTIPLE_REPOS:
            case CONFLICT_SAVED_TO_TEMP_BRANCH:
            case ROOK_NO_LONGER_EXISTS:
            case BOOK_WITH_PREVIOUS_ERROR_AND_NO_LINK:
                actionType = BookAction.Type.ERROR;
                break;
        }
        BookAction action = BookAction.forNow(actionType, status.msg(actionMessageArgument));
        dataRepository.setBookLastActionAndSyncStatus(bookView.getBook().getId(),
                action,
                status.toString());
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public String writeFileToRepo(String content, String repoRelativePath) throws Exception {
        File tempWorkDir = Files.createTempDirectory("orgzlytest").toFile();
        Uri expectedRookUri = Uri.parse(Uri.encode(repoRelativePath, "/"));
        try (Git repoClone = new CloneCommand().setURI(getUri().toString()).setDirectory(tempWorkDir).call()) {
            var targetFile = new File(tempWorkDir, repoRelativePath);
            targetFile.getParentFile().mkdirs();
            MiscUtils.writeStringToFile(content, targetFile);
            repoClone.add().addFilepattern(".").call();
            repoClone.commit().setMessage("").call();
            repoClone.push().call();
        }
        tempWorkDir.delete();
        // Ensure Orgzly's working tree is updated. This is needed when testing getBooks(), which
        // does not update the worktree on its own.
        try (GitTransportSetter transportSetter = preferences.createTransportSetter()) {
            synchronizer.pull(transportSetter);
        }
        return expectedRookUri.toString();
    }

}
