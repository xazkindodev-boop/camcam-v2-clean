package com.zkincamcam.xcam.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VirtualMediaFileResolverTest {
    @Test
    fun resolvesOnlyExistingVirtualMediaFilesInsideAppDirectory() {
        val root = Files.createTempDirectory("xcam-provider").toFile()
        try {
            val media = File(root, "virtual.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            assertEquals(media.canonicalFile, VirtualMediaFileResolver.resolve(root, media.name))
            assertNull(VirtualMediaFileResolver.resolve(root, "private.txt"))
            assertNull(VirtualMediaFileResolver.resolve(root, "virtual.missing"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalAndSeparatorNames() {
        val root = Files.createTempDirectory("xcam-provider").toFile()
        try {
            assertNull(VirtualMediaFileResolver.resolve(root, "virtual...\\outside"))
            assertNull(VirtualMediaFileResolver.resolve(root, "virtual.../outside"))
            assertNull(VirtualMediaFileResolver.resolve(root, "virtual..."))
        } finally {
            root.deleteRecursively()
        }
    }
}
