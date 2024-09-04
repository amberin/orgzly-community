package com.orgzly.android.git;

import static com.orgzly.android.ui.AppSnackbarUtils.showSnackbar;

import android.app.Activity;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.orgzly.BuildConfig;
import com.orgzly.android.App;
import com.orgzly.android.util.LogUtils;
import com.orgzly.android.util.MiscUtils;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.util.List;
import java.util.Objects;

public class GitFileSynchronizer {
    private final static String TAG = GitFileSynchronizer.class.getName();
    public final static String CONFLICT_BRANCH = "ORGZLY_CONFLICT";
    private final Git git;
    private final GitPreferences preferences;
    private final Activity currentActivity = App.getCurrentActivity();


    public GitFileSynchronizer(Git g, GitPreferences prefs) {
        git = g;
        preferences = prefs;
    }

    public void retrieveLatestVersionOfFile(
            String repositoryPath, File destination) throws IOException {
        MiscUtils.copyFile(workTreeFile(repositoryPath), destination);
    }

    public void hardResetToRemoteHead() throws GitAPIException {
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/" + preferences.branchName()).call();
    }

    public InputStream openRepoFileInputStream(String repositoryPath) throws FileNotFoundException {
        return new FileInputStream(workTreeFile(repositoryPath));
    }

    private AbstractTreeIterator prepareTreeParser(RevCommit commit) throws IOException {
        // from the commit we can build the tree which allows us to construct the TreeParser
        Repository repo = git.getRepository();
        try (RevWalk walk = new RevWalk(repo)) {
            RevTree tree = walk.parseTree(commit.getTree().getId());
            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            try (ObjectReader reader = repo.newObjectReader()) {
                treeParser.reset(reader, tree.getId());
            }
            walk.dispose();
            return treeParser;
        }
    }

    public List<DiffEntry> getCommitDiff(RevCommit oldCommit, RevCommit newCommit) throws GitAPIException, IOException {
        return git.diff()
                .setShowNameAndStatusOnly(true)
                .setOldTree(prepareTreeParser(oldCommit))
                .setNewTree(prepareTreeParser(newCommit))
                .call();
    }

    public RevCommit fetch(GitTransportSetter transportSetter) throws IOException {
        try {
            if (BuildConfig.LOG_DEBUG) {
                LogUtils.d(TAG, String.format("Fetching Git repo from %s", preferences.remoteUri()));
            }
            transportSetter.setTransport(
                    git.fetch()
                            .setRemote(preferences.remoteName())
                            .setRemoveDeletedRefs(true)
            ).call();
        } catch (GitAPIException e) {
            Log.e(TAG, e.toString());
            throw new IOException(e.getMessage());
        }
        String currentBranch = git.getRepository().getBranch();
        return getCommit("origin/" + currentBranch);
    }

    public boolean pull(GitTransportSetter transportSetter) throws IOException {
        ensureRepoIsClean();
        try {
            RevCommit mergeTarget = fetch(transportSetter);
            return doMerge(mergeTarget);
        } catch (GitAPIException e) {
            Log.e(TAG, e.toString());
        }
        return false;
    }

    public RevCommit getRemoteHead() throws IOException {
        return getCommit("origin/" + git.getRepository().getBranch());
    }

    public RebaseResult rebase() throws IOException {
        ensureRepoIsClean();
        RebaseResult result;
        try {
            result = git.rebase().setUpstream("origin/" + git.getRepository().getBranch()).call();
            if (!result.getStatus().isSuccessful()) {
                git.rebase().setOperation(RebaseCommand.Operation.ABORT).call();
            }
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private boolean doMerge(RevCommit mergeTarget) throws IOException, GitAPIException {
        MergeResult result = git.merge().include(mergeTarget).call();
        if (result.getMergeStatus().equals(MergeResult.MergeStatus.CONFLICTING)) {
            gitResetMerge();
            return false;
        }
        return true;
    }

    public void pushToConflictBranch(GitTransportSetter transportSetter) {
        RefSpec refSpec = new RefSpec("HEAD:refs/heads/" + CONFLICT_BRANCH);
        final var pushCommand = transportSetter.setTransport(git.push().setRefSpecs(refSpec).setForce(true));
        final Object monitor = new Object();
        App.EXECUTORS.diskIO().execute(() -> {
            try {
                Iterable<PushResult> results = (Iterable<PushResult>) pushCommand.call();
                if (!results.iterator().next().getMessages().isEmpty()) {
                    if (currentActivity != null) {
                        showSnackbar(currentActivity, results.iterator().next().getMessages());
                    }
                }
                synchronized (monitor) {
                    monitor.notify();
                }
            } catch (GitAPIException e) {
                if (currentActivity != null) {
                    showSnackbar(currentActivity, String.format("Failed to push to conflict branch: %s", e.getMessage()));
                }
            }
        });
        synchronized (monitor) {
            try {
                monitor.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public RemoteRefUpdate pushWithResult(GitTransportSetter transportSetter) throws Exception {
        final var pushCommand = transportSetter.setTransport(
                git.push().setRemote(preferences.remoteName()));
        final Object monitor = new Object();
        final RemoteRefUpdate[] result = new RemoteRefUpdate[1];
        final Exception[] exception = new Exception[1];

        App.EXECUTORS.diskIO().execute(() -> {
            try {
                Iterable<PushResult> results = (Iterable<PushResult>) pushCommand.call();
                result[0] = results.iterator().next().getRemoteUpdates().iterator().next();
                synchronized (monitor) {
                    monitor.notify();
                }
            } catch (Exception e) {
                exception[0] = e;
            }
        });
        synchronized (monitor) {
            try {
                monitor.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, e.toString());
            }
        }
        if (exception[0] != null)
            throw exception[0];
        return result[0];
    }

    public void push(GitTransportSetter transportSetter) {
        final var pushCommand = transportSetter.setTransport(
                git.push().setRemote(preferences.remoteName()));
        final Object monitor = new Object();

        if (BuildConfig.LOG_DEBUG) {
            String currentBranch = "UNKNOWN_BRANCH";
            try {
                currentBranch = git.getRepository().getBranch();
            } catch (IOException ignored) {}
            LogUtils.d(TAG, "Pushing branch " + currentBranch + " to " + preferences.remoteUri());
        }
        App.EXECUTORS.diskIO().execute(() -> {
            try {
                Iterable<PushResult> results = (Iterable<PushResult>) pushCommand.call();
                // org.eclipse.jgit.api.PushCommand swallows some errors without throwing exceptions.
                if (!results.iterator().next().getMessages().isEmpty()) {
                    if (currentActivity != null) {
                        showSnackbar(currentActivity, results.iterator().next().getMessages());
                    }
                }
                synchronized (monitor) {
                    monitor.notify();
                }
            } catch (GitAPIException e) {
                if (currentActivity != null) {
                    showSnackbar(
                            currentActivity,
                            String.format("Failed to push to remote: %s", e.getMessage())
                    );
                }
            }
        });
        synchronized (monitor) {
            try {
                monitor.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void gitResetMerge() throws IOException, GitAPIException {
        git.getRepository().writeMergeCommitMsg(null);
        git.getRepository().writeMergeHeads(null);
        git.reset().setMode(ResetCommand.ResetType.HARD).call();
    }

    private void ensureDirectoryHierarchy(String repositoryPath) throws IOException {
        if (repositoryPath.contains("/")) {
            File targetDir = workTreeFile(repositoryPath).getParentFile();
            assert targetDir != null;
            if (!(targetDir.exists() || targetDir.mkdirs())) {
                throw new IOException("The directory " + targetDir.getAbsolutePath() + " could " +
                        "not be created");
            }
        }
    }

    public void writeFileAndAddToIndex(File sourceFile, String repoRelativePath) throws IOException {
        if (repoHasUnstagedChanges())
            throw new IOException("Git working tree is in an unclean state; refusing to update.");
        ensureDirectoryHierarchy(repoRelativePath);
        File destinationFile = workTreeFile(repoRelativePath);
        MiscUtils.copyFile(sourceFile, destinationFile);
        try {
            git.add().addFilepattern(repoRelativePath).call();
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    private void commit(String message) throws GitAPIException {
        git.commit().setMessage(message).call();
    }

    public RevCommit currentHead() throws IOException {
        return getCommit(Constants.HEAD);
    }

    public RevCommit getCommit(String identifier) throws IOException {
        Ref target = git.getRepository().findRef(identifier);
        if (target == null) {
            return null;
        }
        if (Objects.equals(identifier, Constants.HEAD) && isEmptyRepo())
            return null;
        return new RevWalk(git.getRepository()).parseCommit(target.getObjectId());
    }

    public RevCommit getLastCommitOfFile(Uri uri) throws GitAPIException {
        String repoRelativePath = uri.getPath();
        return git.log().setMaxCount(1).addPath(repoRelativePath).call().iterator().next();
    }

    public String workTreePath() {
        return git.getRepository().getWorkTree().getAbsolutePath();
    }

    private boolean gitRepoIsClean() {
        try {
            Status status = git.status().call();
            return !status.hasUncommittedChanges();
        } catch (GitAPIException e) {
            return false;
        }
    }

    private boolean repoHasUnstagedChanges() {
        Status status;
        try {
            status = git.status().call();
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
        return (status.getModified().size() > 0 ||
                status.getUntracked().size() > 0 ||
                status.getUntrackedFolders().size() > 0);
    }

    /**
     * If any changes have been staged, commit them, otherwise do nothing.
     * @throws IOException
     */
    @Nullable
    public RevCommit commitAnyStagedChanges() throws IOException {
        if (!gitRepoIsClean()) {
            if (repoHasUnstagedChanges())
                throw new IOException("Git working tree is in an unclean state; refusing to " +
                        "update.");
            try {
                commit("Orgzly update");
                return currentHead();
            } catch (GitAPIException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    private void ensureRepoIsClean() throws IOException {
        if (!gitRepoIsClean())
            throw new IOException("Refusing to update because there are uncommitted changes.");
    }

    public File workTreeFile(String filePath) {
        return new File(workTreePath(), filePath);
    }

    public boolean isEmptyRepo() throws IOException{
        return git.getRepository().exactRef(Constants.HEAD).getObjectId() == null;
    }

    public boolean deleteFileFromRepo(Uri uri, GitTransportSetter transportSetter) throws IOException {
        if (pull(transportSetter)) {
            String repoRelativePath = uri.getPath();
            try {
                git.rm().addFilepattern(repoRelativePath).call();
                if (!gitRepoIsClean())
                    commit(String.format("Orgzly deletion: %s", repoRelativePath));
                return true;
            } catch (GitAPIException e) {
                throw new IOException(String.format("Failed to commit deletion of %s, %s", repoRelativePath, e.getMessage()));
            }
        } else {
            return false;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public boolean renameFileInRepo(String oldPath, String newPath,
                                    GitTransportSetter transportSetter) throws IOException {
        ensureRepoIsClean();
        if (pull(transportSetter)) {
            File oldFile = workTreeFile(oldPath);
            File newFile = workTreeFile(newPath);
            // Abort if destination file exists
            if (newFile.exists()) {
                throw new FileAlreadyExistsException("Repository file " + newPath + " already exists.");
            }
            ensureDirectoryHierarchy(newPath);
            // Copy the file contents and add it to the index
            MiscUtils.copyFile(oldFile, newFile);
            try {
                git.add().addFilepattern(newPath).call();
                if (!gitRepoIsClean()) {
                    // Remove the old file from the Git index
                    git.rm().addFilepattern(oldPath).call();
                    commit(String.format("Orgzly: rename %s to %s", oldPath, newPath));
                    return true;
                }
            } catch (GitAPIException e) {
                throw new IOException("Failed to rename file in repo, " + e.getMessage());
            }
        }
        return false;
    }
}
