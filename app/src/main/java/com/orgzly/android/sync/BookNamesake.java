package com.orgzly.android.sync;

import android.content.Context;

import com.orgzly.android.BookName;
import com.orgzly.android.db.entity.BookView;
import com.orgzly.android.db.entity.Repo;
import com.orgzly.android.repos.VersionedRook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Container for local and all remote books that share the same name.
 */
public class BookNamesake {
    private String name;

    /** Local book. */
    private BookView book;

    /** Remote versioned books. */
    private List<VersionedRook> versionedRooks = new ArrayList<>();

    /** Current remote book that the local one is linking to. */
    private VersionedRook latestLinkedRook;

    private BookSyncStatus status;

    public BookNamesake(String name) {
        this.name = name;
    }

    /**
     * Create links between remote books and any local books with the same name.
     *
     * This method is intended to be used with a limited set of VersionedRooks,
     * to allow syncing repos separately.
     */
    public static Map<String, BookNamesake> getFromVrooks(Context context, List<BookView> books, List<VersionedRook> versionedRooks) {
        Map<String, BookNamesake> namesakes = new HashMap<>();

        /* Add all provided remote books. */
        for (VersionedRook book: versionedRooks) {
            String fileName = BookName.getFileName(context, book.getUri());
            String name = BookName.fromFileName(fileName).getName();

            BookNamesake namesake = new BookNamesake(name);
            namesake.addRook(book);
            namesakes.put(name, namesake);
        }

        /* Add any existing local books. */
        for (BookView book: books) {
            String name = book.getBook().getName();
            BookNamesake namesake = namesakes.get(name);
            if (namesake == null) {
                /* This local book does not have a namesake among the VersionedRooks. If it has a
                repo link, we should ignore it, as it is linked to some other repo than the one
                currently being synced. But if it has no repo link, it is probably just new,
                and we should create a dummy namesake. */
                if (book.getLinkRepo() != null) {
                    continue;
                } else {
                    namesake = new BookNamesake(name);
                    namesakes.put(name, namesake);
                }
            }
            namesake.setBook(book);
        }

        return namesakes;
    }

    public String getName() {
        return name;
    }

    public BookView getBook() {
        return book;
    }

    public void setBook(BookView book) {
        this.book = book;
    }

    // TODO: We are always using only the first element of this list
    public List<VersionedRook> getRooks() {
        return versionedRooks;
    }

    public void addRook(VersionedRook vrook) {
        this.versionedRooks.add(vrook);
    }

    public BookSyncStatus getStatus() {
        return status;
    }

    public VersionedRook getLatestLinkedRook() {
        return latestLinkedRook;
    }

    public void setLatestLinkedRook(VersionedRook linkedVersionedRook) {
        this.latestLinkedRook = linkedVersionedRook;
    }

    public String toString() {
        return "[" + this.getClass().getSimpleName() + " " + name +
                " | " + status +
                " | Local:" + (book != null ? book : "N/A") +
                " | Remotes:" + versionedRooks.size() + "]";
    }


    /**
     * States to consider:
     *
     * - Book exists
     *   - Book is dummy
     *   - Book has a link
     *     - Linked remote book exists
     *   - Book has a last-synced-with remote book
     * - Remote book exists
     */
    /* TODO: Case: Remote book deleted? */
    public void updateStatus(int reposCount) {
        /* Sanity check. Group's name must come from somewhere - local or remote books. */
        if (book == null && versionedRooks.isEmpty()) {
            throw new IllegalStateException("BookNameGroup does not contain any books");
        }

        if (book == null) {
            /* Remote books only */

            if (versionedRooks.size() == 1) {
                status = BookSyncStatus.NO_BOOK_ONE_ROOK;
            } else {
                status = BookSyncStatus.NO_BOOK_MULTIPLE_ROOKS;
            }

            return;

        } else if (versionedRooks.isEmpty()) {
            /* Local book only */

            if (book.getBook().isDummy()) { /* Only dummy exists. */
                status = BookSyncStatus.ONLY_DUMMY;

            } else {
                if (book.hasLink()) { /* Only local book with a link. */
                    status = BookSyncStatus.ONLY_BOOK_WITH_LINK;

                } else { /* Only local book without link. */
                    if (reposCount > 1) {
                        status = BookSyncStatus.ONLY_BOOK_WITHOUT_LINK_AND_MULTIPLE_REPOS;
                    } else {
                        status = BookSyncStatus.ONLY_BOOK_WITHOUT_LINK_AND_ONE_REPO;
                    } // TODO: What about no repos?
                }
            }

            return;
        }

        /* Both local book and one or more remote books exist at this point ... */

        if (book.hasLink()) { // Book has link set.

            VersionedRook latestLinkedRook = getLatestLinkedRookVersion(book, versionedRooks);

            if (latestLinkedRook == null) {
                /* Both local and remote book exist with the same name.
                 * Book has a link, however that link is not pointing to an existing remote book.
                 */
                status = BookSyncStatus.BOOK_WITH_LINK_AND_ROOK_EXISTS_BUT_LINK_POINTING_TO_DIFFERENT_ROOK;
                // TODO: So what's the problem? Just save it then? But can we just overwrite whatever is link pointing too?
                return;
            }
            setLatestLinkedRook(latestLinkedRook);


            if (book.getBook().isDummy()) {
                status = BookSyncStatus.DUMMY_WITH_LINK;
                return;
            }

            if (book.getSyncedTo() == null) {
                status = BookSyncStatus.CONFLICT_BOOK_WITH_LINK_AND_ROOK_BUT_NEVER_SYNCED_BEFORE;
                return;
            }

            if (! book.getSyncedTo().getUri().equals(latestLinkedRook.getUri())) {
                status = BookSyncStatus.CONFLICT_LAST_SYNCED_ROOK_AND_LATEST_ROOK_ARE_DIFFERENT;
                return;
            }

            /* Same revision, there was no remote change. */
            if (book.getSyncedTo().getRevision().equals(latestLinkedRook.getRevision())) {
                /* Revision did not change. */

                if (book.isOutOfSync()) { // Local change
                    status = BookSyncStatus.BOOK_WITH_LINK_LOCAL_MODIFIED;
                } else {
                    status = BookSyncStatus.NO_CHANGE;
                }

            } else { /* Remote book has been modified. */
                if (book.isOutOfSync()) { // Local change
                    status = BookSyncStatus.CONFLICT_BOTH_BOOK_AND_ROOK_MODIFIED;
                } else {
                    status = BookSyncStatus.BOOK_WITH_LINK_AND_ROOK_MODIFIED;
                }
            }

        } else { /* Local book without link. */

            if (! book.getBook().isDummy()) {
                status = BookSyncStatus.BOOK_WITHOUT_LINK_AND_ONE_OR_MORE_ROOKS_EXIST;

            } else {
                if (versionedRooks.size() == 1) {
                    status = BookSyncStatus.DUMMY_WITHOUT_LINK_AND_ONE_ROOK;
                } else {
                    status = BookSyncStatus.DUMMY_WITHOUT_LINK_AND_MULTIPLE_ROOKS;
                }
            }
        }
    }

    /** Find latest (current) remote book that local one links to. */
    private VersionedRook getLatestLinkedRookVersion(BookView bookView, List<VersionedRook> vrooks) {
        Repo linkRepo = bookView.getLinkRepo();

        if (linkRepo != null) {
            for (VersionedRook vrook : vrooks) {
                if (linkRepo.getUrl().equals(vrook.getRepoUri().toString())){
                    return vrook;
                }
            }
        }

        return null;
    }

    /** Intended for use with IntegrallySyncedRepos
     *
     * @param newStatus The new status to set
     */
    public void setStatus(BookSyncStatus newStatus) {
        status = newStatus;
    }
}
