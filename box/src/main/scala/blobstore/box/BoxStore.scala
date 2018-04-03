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
package box


import java.io.{InputStream, PipedInputStream, PipedOutputStream}

import cats.effect.Effect
import com.box.sdk._
import fs2.{Sink, Stream}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

case class BoxStore[F[_]](api: BoxAPIConnection, rootFolderId: String)(implicit F: Effect[F], ec: ExecutionContext) extends Store[F] {

  val rootFolder = new BoxFolder(api, rootFolderId)

  /**
    * Case sensitive
    * @param path: path.root is folder ID, path.key is path (by names, not IDs) to folder or file
    * @return
    */
  def boxItemAtPath(path: Path): Option[BoxItem] = {
    boxItemAtPath(rootFolder, path.root :: path.key.split("/").toList)
  }

  def boxItemAtPath(parentFolder: BoxFolder, pathParts: List[String]): Option[BoxItem] = {
    pathParts match {
      case Nil => None
      case head::Nil => {
        parentFolder.getChildren.asScala.find(info => info.getName.equals(head))
          .map(_.getResource.asInstanceOf[BoxItem])
      }
      case head::tail => {
        parentFolder.getChildren.asScala.find(info => info.getName.equals(head))
          .flatMap(info => boxItemAtPath(new BoxFolder(api, info.getID), tail))
      }
    }
  }

  def boxFileAtPath(path: Path): Option[BoxFile] = {
    boxItemAtPath(path).flatMap {
      case file: BoxFile => Some(file)
      case _ => None
    }
  }

  def boxFolderAtPath(path: Path): Option[BoxFolder] = {
    boxItemAtPath(path).flatMap {
      case folder: BoxFolder => Some(folder)
      case _ => None
    }
  }


  /**
    * List paths. See [[StoreOps.ListOps]] for convenient listAll method.
    *
    * @param path to list
    * @return stream of Paths. Implementing stores must guarantee that returned Paths
    *         have correct values for size, isDir and lastModified.
    */
  override def list(path: Path): fs2.Stream[F, Path] = {
    Stream.eval(
      F.delay(boxFolderAtPath(path).map(_.getChildren.iterator().asScala))
    ).flatMap(opt => opt.map(it => Stream.fromIterator(it)).getOrElse(Stream.fromIterator(Iterator.empty)))
      .map(item => {
        val isDir = item.isInstanceOf[BoxFolder#Info]
        val itemRoot = path.root + path.key
        Path(itemRoot, item.getName, Some(item.getSize), isDir, Some(item.getModifiedAt))
      })
  }

  /**
    * Get bytes for the given Path. See [[StoreOps.GetOps]] for convenient get and getContents methods.
    *
    * @param path      to get
    * @param chunkSize bytes to read in each chunk.
    * @return stream of bytes
    */
override def get(path: Path, chunkSize: Int): fs2.Stream[F, Byte] = {
  val init: F[(PipedOutputStream, InputStream)] = F.delay {
    val os = new PipedOutputStream()
    val is = new PipedInputStream(os)
    (os, is)
  }

  val consume: ((PipedOutputStream, InputStream)) => Stream[F, Byte] = bothStreams => for {
      opt <- Stream.eval(F.delay(boxFileAtPath(path)))

      dl = opt.map(file => Stream.eval(F.delay(file.download(bothStreams._1)))).getOrElse(Stream.fromIterator(Iterator.empty))
      readInput = fs2.io.readInputStream(F.delay(bothStreams._2), chunkSize, closeAfterUse = true)

      // really not sure about this
      s <- dl concurrently readInput
      b <- readInput

    } yield b

  val release: ((PipedOutputStream, InputStream)) => F[Unit] = ios => F.delay {
    ios._2.close()
    ios._1.close()
  }

  Stream.bracket(init)(consume, release)
}

  /**
    * Provides a Sink that writes bytes into the provided path. See [[StoreOps.PutOps]] for convenient put String
    * and put file methods.
    *
    * It is highly recommended to provide [[Path.size]] when writing as it allows for optimizations in some store.
    * Specifically, S3Store will behave very poorly if no size is provided as it will load all bytes in memory before
    * writing content to S3 server.
    *
    * @param path to put
    * @return sink of bytes
    */
override def put(path: Path): Sink[F, Byte] = ???

  /**
    * Moves bytes from srcPath to dstPath. Stores should optimize to use native move functions to avoid data transfer.
    *
    * @param src path
    * @param dst path
    * @return F[Unit]
    */
  override def move(src: Path, dst: Path): F[Unit] = ???

  /**
    * Copies bytes from srcPath to dstPath. Stores should optimize to use native copy functions to avoid data transfer.
    *
    * @param src path
    * @param dst path
    * @return F[Unit]
    */
  override def copy(src: Path, dst: Path): F[Unit] = ???

  /**
    * Remove byte for given path.
    *
    * @param path to remove
    * @return F[Unit]
    */
  override def remove(path: Path): F[Unit] = ???
}
