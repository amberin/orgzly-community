package com.orgzly.android.espresso;

import androidx.test.core.app.ActivityScenario;

import com.orgzly.R;
import com.orgzly.android.OrgzlyTest;
import com.orgzly.android.TestUtils;
import com.orgzly.android.db.entity.Repo;
import com.orgzly.android.repos.ContentRepo;
import com.orgzly.android.repos.RepoType;
import com.orgzly.android.repos.SyncRepo;

import org.eclipse.jgit.ignore.IgnoreNode;
import org.junit.Before;
import org.junit.Test;

public class IgnoreFileTest extends OrgzlyTest {

    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @Test
    public void TestGetIgnores() {
        for (RepoType repoType : RepoType.values()) {
            
        }
    }
}
