package com.ignite.gateway.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class GitSyncServiceTest {

    @Test
    void containmentAcceptsDirectChildMatchingName() throws Exception {
        File root = Files.createTempDirectory("sites").toFile();
        File dir = new File(root, "my_site");
        assertTrue(dir.mkdir());
        assertTrue(GitSyncService.isSafeSiteDir(root, dir, "my site"));
    }

    @Test
    void containmentRejectsRootTraversalAndMismatch() throws Exception {
        File root = Files.createTempDirectory("sites").toFile();
        File other = new File(root, "other");
        assertTrue(other.mkdir());
        assertFalse(GitSyncService.isSafeSiteDir(root, root, "sites"));
        assertFalse(GitSyncService.isSafeSiteDir(root, new File(root, "../x"), "x"));
        assertFalse(GitSyncService.isSafeSiteDir(root, other, "different"));
        assertFalse(GitSyncService.isSafeSiteDir(root, other, null));
    }

    @Test
    void containmentRejectsSymlinkEscape() throws Exception {
        File root = Files.createTempDirectory("sites").toFile();
        File outside = Files.createTempDirectory("outside").toFile();
        File link = new File(root, "evil");
        Files.createSymbolicLink(link.toPath(), outside.toPath());
        assertFalse(GitSyncService.isSafeSiteDir(root, link, "evil"));
    }

    @Test
    void redactRemovesTokenInUrlAndLiteral() {
        assertEquals("fatal: https://***@host/x", GitSyncService.redact("fatal: https://ghp_abc@host/x", null));
        assertEquals("bad *** here", GitSyncService.redact("bad sekret here", " sekret "));
    }
}
