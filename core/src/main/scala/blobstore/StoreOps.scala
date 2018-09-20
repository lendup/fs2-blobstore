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

import java.nio.charset.Charset

import cats.effect.{ConcurrentEffect, ContextShift, Effect, Sync}
import fs2.{Pipe, Sink, Stream}
import cats.implicits._

import scala.concurrent.ExecutionContext

trait StoreOps {

  implicit class PutOps[F[_]](store: Store[F]) {
    /**
      * Write contents String into path.
      * @param contents String
      * @param path Path to write to
      * @return F[Unit]
      */
    def put(contents: String, path: Path)(implicit F: Sync[F]): F[Unit] =
      for {
        buf <- F.delay(contents.getBytes(Charset.forName("utf-8")))
        _ <- Stream.emits(buf).covary[F].to(store.put(path.copy(size = Option(buf.size.toLong)))).compile.drain
      } yield ()

    /**
      * Write contents of src file into dst Path
      * @param src java.nio.file.Path
      * @param dst Path to write to
      * @return F[Unit]
      */
    def put(src: java.nio.file.Path, dst: Path, blockingExecutionContext: ExecutionContext)(implicit F: Sync[F], CS: ContextShift[F]): F[Unit] =
      fs2.io.file
        .readAll(src, blockingExecutionContext, 4096)
        .to(store.put(dst.copy(size = Option(src.toFile.length))))
        .compile.drain


    /**
      * Put sink that buffers all incoming bytes to local filesystem, computes buffered data size, then puts bytes
      * to store. Useful when uploading data to stores that require content size like S3Store.
      *
      * @param path Path to write to
      * @return Sink[F, Byte] buffered sink
      */
    def bufferedPut(path: Path, blockingExecutionContext: ExecutionContext)(implicit F: ConcurrentEffect[F], CS: ContextShift[F]): Sink[F, Byte] = in =>
      in.through(bufferToDisk(4096, blockingExecutionContext)).flatMap { case (n, s) =>
        s.to(store.put(path.copy(size = Some(n))))
      }
  }

  implicit class GetOps[F[_]](store: Store[F]) {
    /**
      * get with default buffer size of 4kb
      * @param path Path to get
      * @return Stream of Byte
      */
    def get(path: Path): Stream[F, Byte] = store.get(path, 4096)

    /**
      * get src path and write to local file system
      * @param src Path to get
      * @param dst local file to write contents to
      * @return F[Unit]
      */
    def get(src: Path, dst: java.nio.file.Path, blockingExecutionContext: ExecutionContext)(implicit F: Sync[F], CS: ContextShift[F]): F[Unit] =
      store.get(src, 4096).to(fs2.io.file.writeAll(dst, blockingExecutionContext)).compile.drain

    /**
      * getContents with default UTF8 decoder
      * @param path Path to get
      * @return F[String] with file contents
      */
    def getContents(path: Path)(implicit F: Effect[F]): F[String] = getContents(path, fs2.text.utf8Decode)

    /**
      * Decode get bytes from path into a string using decoder and return concatenated string.
      *
      * USE WITH CARE, this loads all file contents into memory.
      *
      * @param path Path to get
      * @param decoder Pipe[F, Byte, String]
      * @return F[String] with file contents
      */
    def getContents(path: Path, decoder: Pipe[F, Byte, String])(implicit F: Effect[F]): F[String] = {
      get(path).through(decoder).compile.toList.map(_.mkString)
    }

  }

  implicit class ListOps[F[_]](store: Store[F]) {

    /**
      * Collect all list results in the same order as the original list Stream
      * @param path Path to list
      * @return F\[List\[Path\]\] with all items in the result
      */
    def listAll(path: Path)(implicit F: Sync[F]): F[List[Path]] = {
        store.list(path).compile.toList
    }
  }

  implicit class TransferOps[F[_]](store: Store[F]) {

    /**
      * Copy value of the given path in this store to the destination store.
      *
      * This is especially useful when transferring content into S3Store that requires to know content
      * size before starting content upload.
      *
      * This method will list items from srcPath, get the file size, put into dstStore with teh give size. If
      * listing contents result in nested directories it will copy files inside dirs recursively.
      *
      * @param dstStore destination store
      * @param srcPath path to transfer from (can be a path to a file or dir)
      * @param dstPath path to transfer to (can be a path to a file or dir, if you are transferring multiple files,
      *                make sure that dstPath.isDir == true, otherwise all files will override destination.
      * @return Stream[F, Int] number of files transfered
      */
    def transferTo(dstStore: Store[F], srcPath: Path, dstPath: Path)(implicit F: Sync[F]): F[Int] = {
      import implicits._
      store.list(srcPath).evalMap(p =>
        if (p.isDir) {
          transferTo(dstStore, p, dstPath / p.filename)
        } else {
          val dp = if (dstPath.isDir) dstPath / p.filename else dstPath
          store.get(p, 4096)
            .to(dstStore.put(dp.copy(size = p.size)))
            .compile.drain
            .as(1)
        }
      ).compile.fold(0)(_ + _)
    }
  }

  implicit class RemoveOps[F[_]](store: Store[F]) {

    /**
      * Remove all files from a store recursively, given a path
      *
      */
    def removeAll(dstPath: Path)(implicit F: Sync[F]): F[Int] = {
      import implicits._
      store.list(dstPath).evalMap(p =>
        if (p.isDir) {
          removeAll(dstPath / p.filename)
        } else {
          val dp = if (dstPath.isDir) dstPath / p.filename else dstPath
          fs2.Stream.eval(store.remove(dp)).compile.drain.as(1)
        }
      ).compile.fold(0)(_ + _)
    }
  }

}
