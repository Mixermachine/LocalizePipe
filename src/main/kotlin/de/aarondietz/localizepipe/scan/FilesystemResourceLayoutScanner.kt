package de.aarondietz.localizepipe.scan

import java.nio.file.Files
import java.nio.file.Path

class FilesystemResourceLayoutScanner {
    fun scanRoot(root: Path): List<ClassifiedResourcePath> {
        if (!Files.exists(root)) {
            return emptyList()
        }

        Files.walk(root).use { stream ->
            return stream
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString() == "strings.xml" }
                .map { path -> path.toString().replace('\\', '/') }
                .map { path -> ResourcePathClassifier.classify(path) }
                .filter { classified -> classified != null }
                .map { classified -> classified!! }
                .sorted(compareBy<ClassifiedResourcePath> { it.kind.name }.thenBy { it.resourceRootPath }
                    .thenBy { it.folderName })
                .toList()
        }
    }
}
