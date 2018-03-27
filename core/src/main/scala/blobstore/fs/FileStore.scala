/*
Copyright 2018 LendUp Global, Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package blobstore
package fs

import java.nio.file.{Path => NioPath, Files, Paths}
import java.util.Date

import scala.collection.JavaConverters._
import cats.implicits._
import cats.effect.Effect
import fs2.{Stream, Sink}

case class FileStore[F[_]](fsroot: NioPath)(implicit F: Effect[F]) extends Store[F] {
  val absRoot: String = fsroot.toAbsolutePath.normalize.toString

  override def list(path: Path): fs2.Stream[F, Path] = {
    val isDir = Stream.eval(F.delay(Files.isDirectory(path)))
    val isFile = Stream.eval(F.delay(Files.exists(path)))

    val files = Stream.eval(F.delay(Files.list(path)))
      .flatMap(x => Stream.fromIterator(x.iterator.asScala))
      .evalMap(x => F.delay(
        Path(x.toAbsolutePath.toString.replaceFirst(absRoot, "")).copy(
          size = Option(Files.size(x)),
          isDir = Files.isDirectory(x),
          lastModified = Option(new Date(Files.getLastModifiedTime(path).toMillis))
        )
      ))

    val file = fs2.Stream.eval {
      F.delay {
        path.copy(
          size = Option(Files.size(path)),
          lastModified = Option(new Date(Files.getLastModifiedTime(path).toMillis))
        )
      }
    }

    isDir.ifM(files, isFile.ifM(file, Stream.empty))
  }

  override def get(path: Path, chunkSize: Int): fs2.Stream[F, Byte] = fs2.io.file.readAll(path, chunkSize)

  override def put(path: Path): Sink[F, Byte] = { in =>
    val mkdir = Stream.eval(F.delay(Files.createDirectories(_toNioPath(path).getParent)).as(true))
    mkdir.ifM(
      fs2.io.file.writeAll(path).apply(in),
      Stream.raiseError(new Exception(s"failed to create dir: $path"))
    )
  }

  override def move(src: Path, dst: Path): F[Unit] = F.delay(Files.move(src, dst)).void

  override def copy(src: Path, dst: Path): F[Unit] = {
    val mkdir = Stream.eval(F.delay(Files.createDirectories(_toNioPath(dst).getParent)).as(true))
    mkdir.ifM(
      F.delay(Files.copy(src, dst)).void,
      Stream.raiseError(new Exception(s"failed to create dir: $dst"))
    )
  }

  override def remove(path: Path): F[Unit] = F.delay(Files.delete(path))

  implicit private def _toNioPath(path: Path): NioPath =
    Paths.get(absRoot, path.root, path.key)

}
