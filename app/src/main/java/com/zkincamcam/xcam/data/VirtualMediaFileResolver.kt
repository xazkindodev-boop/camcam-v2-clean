package com.zkincamcam.xcam.data

import java.io.File

internal object VirtualMediaFileResolver {
    fun resolve(filesDirectory: File, fileName: String): File? {
        if (!fileName.startsWith("virtual.") ||
            fileName.contains('/') ||
            fileName.contains('\\') ||
            fileName == "virtual." ||
            fileName.contains("..")
        ) {
            return null
        }

        val root = filesDirectory.canonicalFile
        val file = File(root, fileName).canonicalFile
        return file.takeIf { it.parentFile == root && it.isFile }
    }
}
