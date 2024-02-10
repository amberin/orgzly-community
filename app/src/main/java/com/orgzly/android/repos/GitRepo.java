package com.orgzly.android.repos;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.orgzly.BuildConfig;
import com.orgzly.R;
import com.orgzly.android.BookFormat;
import com.orgzly.android.BookName;
import com.orgzly.android.data.DataRepository;
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
import com.orgzly.android.util.LogUtils;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class GitRepo implements SyncRepo, IntegrallySyncedRepo {
    private final static String TAG = GitRepo.class.getName();
    private final long repoId;

    /**
     * Used as cause when we try to clone into a non-empty directory
     */
    public static class DirectoryNotEmpty extends Exception {
        public File dir;

        DirectoryNotEmpty(File dir) {
            this.dir = dir;
        }
    }

    private String mainBranch() {
        return preferences.branchName();
    }

    public static GitRepo getInstance(RepoWithProps props, Context context) throws IOException {
        // TODO: This doesn't seem to be implemented in the same way as WebdavRepo.kt, do
        //  we want to store configuration data the same way they do?
        Repo repo = props.getRepo();
        Uri repoUri = Uri.parse(repo.getUrl());
        RepoPreferences repoPreferences = new RepoPreferences(context, repo.getId(), repoUri);
        GitPreferencesFromRepoPrefs prefs = new GitPreferencesFromRepoPrefs(repoPreferences);

        // TODO: Build from info

        return build(props.getRepo().getId(), prefs, false);
    }

    private static GitRepo build(long id, GitPreferences prefs, boolean clone) throws IOException {
        Git git = ensureRepositoryExists(prefs, clone, null);

        StoredConfig config = git.getRepository().getConfig();
        config.setString("remote", prefs.remoteName(), "url", prefs.remoteUri().toString());
        config.setString("user", null, "name", prefs.getAuthor());
        config.setString("user", null, "email", prefs.getEmail());
        config.setString("gc", null, "auto", "1500");
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
            throw new IOException(String.format("The directory %s does not exist", directoryFile.toString()), new FileNotFoundException());
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
            throw new IOException(String.format("The directory %s does not exist", directoryFile.toString()), new FileNotFoundException());
        }

        // Using list() can be resource intensive if there's many files, but since we just call it
        // at the time of cloning once we should be fine for now
        if (directoryFile.list().length != 0) {
            throw new IOException(String.format("The directory must be empty"), new DirectoryNotEmpty(directoryFile));
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
            Log.e(TAG, "JGit error:", e);
            throw new IOException(e);
        }
    }

    private Git git;
    private GitFileSynchronizer synchronizer;
    private GitPreferences preferences;

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

    public VersionedRook storeBook(File file, String fileName) throws IOException {
        File destination = synchronizer.repoDirectoryFile(fileName);
        if (destination.exists()) {
            synchronizer.updateAndCommitExistingFile(file, fileName);
        } else {
            synchronizer.addAndCommitNewFile(file, fileName);
        }
        return currentVersionedRook(Uri.EMPTY.buildUpon().appendPath(fileName).build());
    }

    private RevWalk walk() {
        return new RevWalk(git.getRepository());
    }

    RevCommit getCommitFromRevisionString(String revisionString) throws IOException {
        return walk().parseCommit(ObjectId.fromString(revisionString));
    }

    @Override
    public VersionedRook retrieveBook(String fileName, File destination) throws IOException {

        Uri sourceUri = Uri.parse(fileName);

        synchronizer.retrieveLatestVersionOfFile(sourceUri.getPath(), destination);

        return currentVersionedRook(sourceUri);
    }

    private VersionedRook currentVersionedRook(Uri uri) {
        RevCommit commit = null;
        uri = Uri.parse(Uri.decode(uri.toString()));
        try {
            commit = synchronizer.getLastCommitOfFile(uri);
        } catch (GitAPIException e) {
            e.printStackTrace();
        }
        assert commit != null;
        long mtime = (long)commit.getCommitTime()*1000;
        return new VersionedRook(repoId, RepoType.GIT, getUri(), uri, commit.name(), mtime);
    }

    private IgnoreNode getIgnores() throws IOException {
        IgnoreNode ignores = new IgnoreNode();
        File ignoreFile = synchronizer.repoDirectoryFile(".orgzlyignore");
        if (ignoreFile.exists()) {
            FileInputStream in = new FileInputStream(ignoreFile);
            try {
                ignores.parse(in);
            } finally {
                in.close();
            }
        }
        return ignores;
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
        final IgnoreNode ignores = getIgnores();
        walk.setFilter(new TreeFilter() {
            @Override
            public boolean include(TreeWalk walker) {
                final FileMode mode = walker.getFileMode(0);
                final String filePath = walker.getPathString();
                final boolean isDirectory = mode == FileMode.TREE;
                return !(ignores.isIgnored(filePath, isDirectory) == IgnoreNode.MatchResult.IGNORED);
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
            final FileMode mode = walk.getFileMode(0);
            final boolean isDirectory = mode == FileMode.TREE;
            final String filePath = walk.getPathString();
            if (isDirectory)
                continue;
            if (BookName.isSupportedFormatFileName(filePath))
                result.add(
                        currentVersionedRook(
                                Uri.withAppendedPath(Uri.EMPTY, walk.getPathString())));
        }
        return result;
    }

    public Uri getUri() {
        return preferences.remoteUri();
    }

    public void delete(Uri uri) throws IOException {
        if (synchronizer.deleteFileFromRepo(uri)) synchronizer.tryPush();
    }

    public VersionedRook renameBook(Uri oldUri, String newRookName) throws IOException {
        String oldFileName = oldUri.toString().replaceFirst("^/", "");
        String newFileName = newRookName + ".org";
        if (synchronizer.renameFileInRepo(oldFileName, newFileName)) {
            synchronizer.tryPush();
            return currentVersionedRook(Uri.EMPTY.buildUpon().appendPath(newFileName).build());
        } else {
            return null;
        }
    }

    @Override
    public TwoWaySyncResult syncBook(
            Uri uri, VersionedRook current, File fromDB) throws IOException {
        String fileName = uri.getPath().replaceFirst("^/", "");
        boolean merged = true;
        if (current != null) {
            RevCommit rookCommit = getCommitFromRevisionString(current.getRevision());
            if (BuildConfig.LOG_DEBUG) {
                LogUtils.d(TAG, String.format("Syncing file %s, rookCommit: %s", fileName, rookCommit));
            }
            merged = synchronizer.updateAndCommitFileFromRevisionAndMerge(
                    fromDB, fileName,
                    synchronizer.getFileRevision(fileName, rookCommit),
                    rookCommit);

            if (merged) {
                // Our change was successfully merged. Make an attempt
                // to return to the main branch, if we are not on it.
                if (!git.getRepository().getBranch().equals(preferences.branchName())) {
                    synchronizer.attemptReturnToBranch(mainBranch());
                }
            }
        } else {
            Log.w(TAG, "Unable to find previous commit, loading from repository.");
        }
        File writeBackFile = synchronizer.repoDirectoryFile(fileName);
        return new TwoWaySyncResult(
                currentVersionedRook(Uri.EMPTY.buildUpon().appendPath(fileName).build()), merged,
                writeBackFile);
    }

    public void tryPushIfHeadDiffersFromRemote() {
        synchronizer.tryPushIfHeadDiffersFromRemote();
    }

    private String currentBranch() throws IOException {
        return git.getRepository().getBranch();
    }

    @Override
    public @Nullable SyncState syncRepo(@NonNull Context context, @NonNull DataRepository dataRepository) throws IOException {
        /*
        - remoteHasChanges = fetch()
        - if remoteHasChanges:
            - Check out a temp sync branch.
        - for rook : this.getBooks():
            - if there is no local book, loadBookFromRepo and update shown status
            - if corresponding local BookView is out of sync or has no sync:
                - saveBookToRepo
                - store status "local changes"
                - add to list of books with local changes
            - else: store status NO_CHANGE
            - updateDataRepositoryFromStatus
        - if remoteHasChanges:
            - attempt merge with the remote starting branch
            - if we are not on the main branch, attempt a return to it
            - if any merge succeeded:
                - Create a merge diff and loop over the changes:
                    - MODIFY:
                        - loadBookFromRepo
                        - if local book status is not NO_CHANGE, set it to "changes on
                        both sides"
                        - otherwise, set status ROOK_MODIFIED
                    - ADD:
                        - loadBookFromRepo
                        - set status "loaded from ..."
                    - DELETE:
                        - delete local book
            - else:
                - for each book with local changes:
                    - set status "merge conflict"
            - updateDataRepositoryFromStatus
        - for each dataRepository.getBooks():
            - if book has no repo link:
                - saveBookToRepo(getDefaultRepo)
                - set status
            - elif book has repo link but no syncedTo:
                - saveBookToRepo
                - set status
            - updateDataRepositoryFromStatus
        - tryPushIfHeadDiffersFromRemote
        */
        long startTime = System.currentTimeMillis();
        SyncState syncStateToReturn = null;
        String startingBranch = currentBranch();
        boolean remoteHasChanges = false;
        HashSet<BookView> booksWithLocalChanges = new HashSet<>();
        try {
            long preFetchTime = System.currentTimeMillis();
            if (synchronizer.fetch()) {
                remoteHasChanges = true;
                synchronizer.switchToTempSyncBranch();
            }
            long postFetchTime = System.currentTimeMillis();
            Log.i(TAG, String.format("Fetch took %s ms", (postFetchTime - preFetchTime)));
        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
            throw new RuntimeException(e);
        }
        for (VersionedRook rook : getBooks()) {
            String fileName = rook.uri.getPath().replaceFirst("^/", "");
            BookView bookView = dataRepository.getBookView(BookName.fromFileName(fileName).getName());
            BookSyncStatus status;
            if (bookView == null) {
                bookView = dataRepository.loadBookFromRepo(rook.repoId, rook.repoType,
                        rook.repoUri.toString(), fileName);
                status = BookSyncStatus.NO_BOOK_ONE_ROOK;
            } else {
                if (bookView.isOutOfSync() || !bookView.hasSync()) {
                    dataRepository.saveBookToRepo(Objects.requireNonNull(dataRepository.getRepo(repoId)), fileName, bookView, BookFormat.ORG);
                    status = BookSyncStatus.BOOK_WITH_LINK_LOCAL_MODIFIED;
                    booksWithLocalChanges.add(bookView);
                } else {
                    status = BookSyncStatus.NO_CHANGE;
                }
            }
            updateBookStatusInDataRepository(dataRepository, bookView, status);
        }
        if (remoteHasChanges) {
            RevCommit headBeforeMerge = synchronizer.currentHead();
            boolean mergeSucceeded = true;
            try {
                /* We have always switched to a new temp branch at this point. Try to merge with
                the main branch first, and if that fails, try to merge with the starting branch, in
                case we were on a temp branch when the sync started. */
                if (!synchronizer.attemptReturnToBranch(mainBranch())) {
                    mergeSucceeded = synchronizer.attemptReturnToBranch(startingBranch);
                }
            } catch (Exception e) {
                Log.e(TAG, e.getMessage(), e);
                throw new RuntimeException(e);
            }
            if (mergeSucceeded) {
                List<DiffEntry> mergeDiff;
                try {
                    mergeDiff = synchronizer.getCommitDiff(headBeforeMerge, synchronizer.currentHead());
                } catch (GitAPIException e) {
                    Log.e(TAG, e.getMessage(), e);
                    throw new RuntimeException(e);
                }
                for (DiffEntry changedFile : mergeDiff) {
                    BookSyncStatus status = null;
                    Rook rook;
                    BookView bookView = null;
                    switch (changedFile.getChangeType()) {
                        case MODIFY: {
                            rook = currentVersionedRook(Uri.parse(changedFile.getNewPath()));
                            bookView = dataRepository.loadBookFromRepo(rook.repoId, rook.repoType,
                                    rook.repoUri.toString(), rook.uri.getPath().replaceFirst("^/", ""));
                            if (booksWithLocalChanges.contains(bookView)) {
                                status = BookSyncStatus.CONFLICT_BOTH_BOOK_AND_ROOK_MODIFIED;
                            } else {
                                status = BookSyncStatus.BOOK_WITH_LINK_AND_ROOK_MODIFIED;
                            }
                            break;
                        }
                        case ADD: {
                            if (BookName.isSupportedFormatFileName(changedFile.getNewPath())) {
                                rook = currentVersionedRook(Uri.parse(changedFile.getNewPath()));
                                bookView = dataRepository.loadBookFromRepo(rook.repoId, rook.repoType,
                                        rook.repoUri.toString(), rook.uri.getPath().replaceFirst("^/", ""));
                                status = BookSyncStatus.NO_BOOK_ONE_ROOK;
                            }
                            break;
                        }
                        case DELETE: {
                            String fileName = changedFile.getOldPath();
                            bookView =
                                    dataRepository.getBookView(BookName.fromFileName(fileName).getName());
                            if (bookView != null) {
                                dataRepository.deleteBook(bookView, false);
                            }
                            break;
                        }
                        // TODO: Handle RENAME, COPY
                        default:
                            throw new IOException("Unsupported remote change in Git repo (file renamed or copied)");
                    }
                    if (status != null && bookView != null) {
                        updateBookStatusInDataRepository(dataRepository, bookView, status);
                    }
                }
            } else {
                for (BookView bookView : booksWithLocalChanges) {
                    updateBookStatusInDataRepository(dataRepository, bookView,
                            BookSyncStatus.CONFLICT_STAYING_ON_TEMPORARY_BRANCH);
                    syncStateToReturn = SyncState.getInstance(
                            SyncState.Type.FINISHED_WITH_CONFLICTS,
                            "Merge conflict; staying on temporary branch.");  // TODO: String resource
                }
            }
        }
        for (BookView bookView : dataRepository.getBooks()) {
            BookSyncStatus status = null;
            if (!bookView.hasLink()) {
                if (dataRepository.getRepos().size() > 1) {
                    status = BookSyncStatus.ONLY_BOOK_WITHOUT_LINK_AND_MULTIPLE_REPOS;
                } else {
                    String fileName = BookName.getFileName(context, bookView);
                    dataRepository.saveBookToRepo(Objects.requireNonNull(
                            dataRepository.getRepo(repoId)), fileName, bookView, BookFormat.ORG);
                    status = BookSyncStatus.ONLY_BOOK_WITHOUT_LINK_AND_ONE_REPO;
                }
            } else if (!bookView.hasSync()) {
                status = BookSyncStatus.ONLY_BOOK_WITH_LINK;
            }
            if (status != null) {
                updateBookStatusInDataRepository(dataRepository, bookView, status);
            }
        }
        tryPushIfHeadDiffersFromRemote();
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        Log.i(TAG, String.format("Sync took %s ms", duration));
        return syncStateToReturn;
    }

    private void updateBookStatusInDataRepository(DataRepository dataRepository,
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
                actionType = BookAction.Type.ERROR;
                break;
            case CONFLICT_STAYING_ON_TEMPORARY_BRANCH:
                actionType = BookAction.Type.ERROR;
                actionMessageArgument = String.format("branch \"%s\"", currentBranch());
                break;
        }
        BookAction action = BookAction.forNow(actionType, status.msg(actionMessageArgument));
        dataRepository.setBookLastActionAndSyncStatus(bookView.getBook().getId(),
                action,
                status.toString());
    }
}
