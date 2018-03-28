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

import java.util.UUID
import java.nio.file.{Files, Paths, Path => NioPath}

import blobstore.fs.FileStore
import org.scalatest.{BeforeAndAfterAll, FlatSpec, MustMatchers}
import cats.effect.IO
import cats.implicits._
import implicits._

import scala.util.control.NonFatal

trait AbstractStoreTest extends FlatSpec with MustMatchers with BeforeAndAfterAll {

  val transferStoreRootDir: NioPath = Paths.get("tmp/transfer-store-root/")
  val transferStore: Store[IO] = FileStore[IO](transferStoreRootDir)

  val copyStoreRootDir: NioPath = Paths.get("tmp/copy-store-root/")
  val copyStore: Store[IO] = FileStore[IO](copyStoreRootDir)

  val removeStoreRootDir: NioPath = Paths.get("tmp/remove-store-root/")
  val removeStore: Store[IO] = FileStore[IO](removeStoreRootDir)

  val store: Store[IO]
  val root: String

  val testRun: UUID = java.util.UUID.randomUUID()

  behavior of "all stores"
  it should "put, list, get, remove keys" in {
    val dir: Path = dirPath("all")

    // put a random file
    val filename = s"test-${System.currentTimeMillis}.txt"
    val path = writeFile(store, dir)(filename)

    // list to make sure file is present
    val found = store.listAll(path).unsafeRunSync()
    found.size must be(1)
    found.head.toString must be(path.toString)

    // check contents of file
    store.getContents(path).unsafeRunSync() must be(contents(filename))

    // check remove works
    store.remove(path).unsafeRunSync()
    val notFound = store.listAll(path).unsafeRunSync()
    notFound.isEmpty must be(true)
  }

  it should "move keys" in {
    val dir: Path = dirPath("move-keys")
    val src = writeFile(store, dir)(s"src-${System.currentTimeMillis}.txt")
    val dst = dir / s"dst-${System.currentTimeMillis}.txt"

    val test = for {
      l1 <- store.listAll(src)
      l2 <- store.listAll(dst)
      _  <- store.move(src, dst)
      l3 <- store.listAll(src)
      l4 <- store.listAll(dst)
      _  <- store.remove(dst)
    } yield {
      l1.isEmpty must be(false)
      l2.isEmpty must be(true)
      l3.isEmpty must be(true)
      l4.isEmpty must be(false)
    }

    test.unsafeRunSync()

  }

  it should "list multiple keys" in {
    import cats.implicits._

    val dir: Path = dirPath("list-many")

    val paths = (1 to 10)
      .toList
      .map(i => s"filename-$i.txt")
      .map(writeFile(store, dir))

    val exp = paths.map(_.key).toSet

    store.listAll(dir).unsafeRunSync().map(_.key).toSet must be(exp)

    val io: IO[List[Unit]] = paths.map(store.remove).sequence
    io.unsafeRunSync()

    store.listAll(dir).unsafeRunSync().isEmpty must be(true)
  }

  it should "list files and directories correctly" in {
    import cats.implicits._

    val dir: Path = dirPath("list-dirs")
    val paths = List("subdir/file-1.txt", "file-2.txt").map(writeFile(store, dir))
    val exp = paths.map(_.key.replaceFirst("/file-1.txt", "")).toSet

    val ls = store.listAll(dir).unsafeRunSync()
    ls.map(_.key).toSet must be(exp)
    ls.find(_.isDir).map(_.filename) must be(Some("subdir/"))

    val io: IO[List[Unit]] = paths.map(store.remove).sequence
    io.unsafeRunSync()
  }

  it should "transfer individual file to a directory from one store to another" in {
    val srcPath = writeFile(transferStore, dirPath("transfer-single-file-to-dir-src"))("transfer-filename.txt")

    val dstDir = dirPath("transfer-single-file-to-dir-dst")
    val dstPath = dstDir / srcPath.filename

    val test = for {
      i <- transferStore.transferTo(store, srcPath, dstDir)
      c1 <- transferStore.getContents(srcPath).handleError(e => s"FAILED transferStore.getContents $srcPath: ${e.getMessage}")
      c2 <- store.getContents(dstPath).handleError(e => s"FAILED store.getContents $dstPath: ${e.getMessage}")
      _ <- transferStore.remove(srcPath).handleError(_ => ())
      _ <- store.remove(dstPath).handleError(_ => ())
    } yield {
      i must be(1)
      c1 must be(c2)
    }

    test.unsafeRunSync()
  }

  it should "transfer individual file to a file path from one store to another" in {
    val srcPath = writeFile(transferStore, dirPath("transfer-file-to-file-src"))("src-filename.txt")

    val dstPath = dirPath("transfer-file-to-file-dst") / "dst-file-name.txt"

    val test = for {
      i <- transferStore.transferTo(store, srcPath, dstPath)
      c1 <- transferStore.getContents(srcPath)
        .handleError(e => s"FAILED transferStore.getContents $srcPath: ${e.getMessage}")
      c2 <- store.getContents(dstPath)
        .handleError(e => s"FAILED store.getContents $dstPath: ${e.getMessage}")
      _ <- transferStore.remove(srcPath).handleError(_ => ())
      _ <- store.remove(dstPath).handleError(_ => ())
    } yield {
      i must be(1)
      c1 must be(c2)
    }

    test.unsafeRunSync()
  }

  it should "transfer directory to a directory path from one store to another" in {
    val srcDir = dirPath("transfer-dir-to-dir-src")
    val dstDir = dirPath("transfer-dir-to-dir-dst")

    val paths = (1 to 10)
      .toList
      .map(i => s"filename-$i.txt")
      .map(writeFile(transferStore, srcDir))

    val test = for {
      i <- transferStore.transferTo(store, srcDir, dstDir)
      c1 <- paths.map(p => transferStore.getContents(p)
        .handleError(e => s"FAILED transferStore.getContents $p: ${e.getMessage}")).sequence
      c2 <- paths.map(p => store.getContents(dstDir / p.filename)
        .handleError(e => s"FAILED store.getContents ${dstDir / p.filename}: ${e.getMessage}")).sequence
      _ <- paths.map(transferStore.remove(_).handleError(_ => ())).sequence
      _ <- paths.map(p => store.remove(dstDir / p.filename).handleError(_ => ())).sequence
    } yield {
      i must be(10)
      c1 must be(c2)
    }

    test.unsafeRunSync()
  }

  it should "transfer directories recursively from one store to another" in {
    val srcDir = dirPath("transfer-dir-rec-src")
    val dstDir = dirPath("transfer-dir-rec-dst")

    val paths1 = (1 to 5)
      .toList
      .map(i => s"filename-$i.txt")
      .map(writeFile(transferStore, srcDir))

    val paths2 = (6 to 10)
      .toList
      .map(i => s"subdir/filename-$i.txt")
      .map(writeFile(transferStore, srcDir))

    val paths = paths1 ++ paths2

    val test = for {
      i <- transferStore.transferTo(store, srcDir, dstDir)
      c1 <-
        paths.map(
          p => transferStore.getContents(p).handleError(e => s"FAILED transferStore.getContents $p: ${e.getMessage}")
        ).sequence
      c2 <- (
        paths1.map(
          p => store.getContents(dstDir / p.filename)
            .handleError(e => s"FAILED store.getContents ${dstDir / p.filename}: ${e.getMessage}")
        ) ++
          paths2.map(
            p => store.getContents(dstDir / "subdir" / p.filename)
              .handleError(e => s"FAILED store.getContents ${dstDir / "subdir" / p.filename}: ${e.getMessage}")
          )
        ).sequence
      _ <- paths.map(transferStore.remove(_).handleError(_ => ())).sequence
      _ <- paths1.map(p => store.remove(dstDir / p.filename).handleError(_ => ())).sequence
      _ <- paths2.map(p => store.remove(dstDir / "subdir" / p.filename).handleError(_ => ())).sequence
    } yield {
      i must be(10)
      c1.mkString("\n") must be(c2.mkString("\n"))
    }

    test.unsafeRunSync()
  }

  it should "copy files in a store from one directory to another" in {
    val srcDir = dirPath("copy-dir-to-dir-src")
    val dstDir = dirPath("copy-dir-to-dir-dst")

    (1 to 10)
      .toList
      .map(i => s"filename-$i.txt")
      .map(writeFile(copyStore, srcDir))

    val test = for {
      _ <- copyStore.copy(srcDir, dstDir)
      c1 <- copyStore.getContents(srcDir)
        .handleError(e => s"FAILED copyStore.getContents: ${e.getMessage}")
      c2 <- copyStore.getContents(dstDir)
        .handleError(e => s"FAILED copyStore.getContents: ${e.getMessage}")
    } yield {
      c1.mkString("\n") must be(c2.mkString("\n"))
    }

    test.unsafeRunSync()
  }

  // TODO this doesn't test recursive directories. Once listRecursively() is implemented we can fix this
  it should "remove all should remove all files in a directory" in {
    val srcDir = dirPath("rm-dir-to-dir-src")

    (1 to 10)
      .toList
      .map(i => s"filename-$i.txt")
      .map(writeFile(removeStore, srcDir))

    removeStore.removeAll(srcDir).unsafeRunSync()

    removeStore.list(Path(removeStoreRootDir.toString))
      .compile.drain.unsafeRunSync().isEmpty must be(true)
  }

  def dirPath(name: String): Path = Path(s"$root/test-$testRun/$name/")

  def contents(filename: String) = s"file contents to upload: $filename"

  def writeFile(store: Store[IO], tmpDir: Path)(filename: String): Path = {
    val path = tmpDir / filename
    store.put(contents(filename), path).unsafeRunSync()
    path
  }

  // remove dirs created by AbstractStoreTest
  override def afterAll(): Unit = {
    val clean = List("transfer-dir-to-dir-src", "transfer-file-to-file-src", "transfer-single-file-to-dir-src",
      "transfer-dir-rec-src/subdir/", "transfer-dir-rec-src")
      .map(t => transferStoreRootDir.resolve(s"$root/test-$testRun/$t")) ++
      List(transferStoreRootDir.resolve(s"$root/test-$testRun"), transferStoreRootDir.resolve(s"$root"), transferStoreRootDir)

    clean.foreach(p => try { Files.delete(p) } catch { case NonFatal(_) => /* noop */ })
  }

}