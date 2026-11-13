// SPDX-License-Identifier: GPL-3.0-or-later

package hath.base;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Iterator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Publishes a complete ZIP before removing any original gallery files. */
final class GalleryArchive {
    static Path create(Path source) throws IOException {
        if(!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Gallery source must be a directory, not a symbolic link");
        }
        Path target = source.resolveSibling(source.getFileName() + ".zip");
        Path temporary = Files.createTempFile(source.getParent(), ".hath-gallery-", ".zip.tmp");
        try {
            try(ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary));
                Stream<Path> paths = Files.walk(source)) {
                Iterator<Path> files = paths.iterator();
                while(files.hasNext()) {
                    Path file = files.next();
                    if(Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    if(!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Refusing non-regular gallery file: " + file);
                    }
                    String name = source.relativize(file).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
            // Both paths share a directory/filesystem. Never publish an incomplete ZIP.
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch(AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            try(Stream<Path> paths = Files.walk(source)) {
                Iterator<Path> files = paths.sorted(Comparator.reverseOrder()).iterator();
                while(files.hasNext()) Files.delete(files.next());
            }
            return target;
        } catch(java.io.UncheckedIOException e) {
            throw e.getCause();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
