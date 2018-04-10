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


import java.io.{InputStream, OutputStream, PipedInputStream, PipedOutputStream}

import cats.effect.Effect
import com.box.sdk._
import fs2.{Sink, Stream}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global

// TODO: allow client to provide an ExecutionContext
case class BoxStore[F[_]](api: BoxAPIConnection, rootFolderId: String)(implicit F: Effect[F]) extends Store[F] {

  val rootFolder = new BoxFolder(api, rootFolderId)

  def boxItemAtPath(path: Path): Option[BoxItem] = {
    boxItemAtPath(rootFolder, path.root :: path.key.split("/").toList)
  }

  def boxItemAtPath(parentFolder: BoxFolder, pathParts: List[String]): Option[BoxItem] = {
    pathParts match {
      case Nil => None
      case head::Nil =>
        parentFolder.getChildren.asScala.find(info => info.getName.equalsIgnoreCase(head))
          .map(_.getResource.asInstanceOf[BoxItem])
      case head::tail =>
        parentFolder.getChildren.asScala.find(info => info.getName.equalsIgnoreCase(head))
          .flatMap(info => boxItemAtPath(new BoxFolder(api, info.getID), tail))
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
    for {
      boxFolder <- Stream.eval(F.delay(boxFolderAtPath(path)))
      item <- Stream.fromIterator(boxFolder.map(_.getChildren.iterator().asScala).getOrElse(Iterator.empty))
      isDir = item.isInstanceOf[BoxFolder#Info]
      itemRoot = path.root + "/" + path.key
      p = Path(itemRoot, item.getName, Some(item.getSize), isDir, Some(item.getModifiedAt))
    } yield p
  }

  /**
    * Get bytes for the given Path. See [[StoreOps.GetOps]] for convenient get and getContents methods.
    *
    * @param path      to get
    * @param chunkSize bytes to read in each chunk.
    * @return stream of bytes
    */
  override def get(path: Path, chunkSize: Int): fs2.Stream[F, Byte] = {
    val init: F[(OutputStream, InputStream)] = F.delay {
      val is = new PipedInputStream()
      val os = new PipedOutputStream(is)
      (os, is)
    }

    val consume: ((OutputStream, InputStream)) => Stream[F, Byte] = bothStreams => for {
        opt <- Stream.eval(F.delay(boxFileAtPath(path)))

        dl = opt.map(file => Stream.eval(F.delay({
          file.download(bothStreams._1)
          bothStreams._1.close()
        }))).getOrElse(Stream.fromIterator(Iterator.empty))

        readInput = fs2.io.readInputStream(F.delay(bothStreams._2), chunkSize, closeAfterUse = true)

        s <- readInput concurrently dl
    } yield s

    val release: ((OutputStream, InputStream)) => F[Unit] = ios => F.delay {
      ios._1.close()
      ios._2.close()
    }

    Stream.bracket(init)(consume, release)
  }

  /**
    * Creates a BoxFolder at this path and all folders along this path.
    * If the path already exists, this will simply traverse the path and return the folder at this path.
    * NOTE: this method makes Box API calls
    * @param parentFolder
    * @param pathParts
    * @return a BoxFolder at this path
    */
  def putFolderAtPath(parentFolder: BoxFolder, pathParts: List[String]): BoxFolder = {
    pathParts match {
      case Nil => parentFolder
      case head :: tail =>
        val matchingItem = parentFolder.getChildren.asScala
          .find(_.getName.equalsIgnoreCase(head))
          .getOrElse(parentFolder.createFolder(head))

        if (!matchingItem.getResource.isInstanceOf[BoxFolder]) {
          throw new Exception("Item exists at path but was not folder")
        } else {
          val folder = matchingItem.getResource.asInstanceOf[BoxFolder]
          putFolderAtPath(folder, tail)
        }
    }
  }

  def splitPath(path: Path): (List[String], String) = {
    val fullPath = path.root :: path.key.split("/").toList
    val pathToParentFolder = fullPath.dropRight(1)
    val key = fullPath.takeRight(1).head
    (pathToParentFolder, key)
  }

  /**
    * Provides a Sink that writes bytes into the provided path. See [[StoreOps.PutOps]] for convenient put String
    * and put file methods.
    *
    * It is highly recommended to provide [[Path.size]] when writing as it allows for optimizations in some store.
    *
    * @param path to put
    * @return sink of bytes
    */
  // TODO: check for existence of file and delete it?
  override def put(path: Path): Sink[F, Byte] = { in =>
    val pathSplit = splitPath(path)

    val init: F[(OutputStream, InputStream, BoxFolder)] = F.delay {
      val parentFolder = putFolderAtPath(rootFolder, pathSplit._1)
      val os = new PipedOutputStream()
      val is = new PipedInputStream(os)
      (os, is, parentFolder)
    }

    val consume: ((OutputStream, InputStream, BoxFolder)) => Stream[F, Unit] = ios => {
      val putToBox = Stream.eval(F.delay({
        ios._3.uploadFile(ios._2, pathSplit._2)
        ()
      }))
      val writeBytes = _writeAllToOutputStream1(in, ios._1).stream ++ Stream.eval(F.delay(ios._1.close()))

      putToBox concurrently writeBytes
    }

    val release: ((OutputStream, InputStream, BoxFolder)) => F[Unit] = ios => F.delay {
      ios._2.close()
      ios._1.close()
    }

    Stream.bracket(init)(consume, release)
  }

  /**
    * Moves bytes from srcPath to dstPath. Stores should optimize to use native move functions to avoid data transfer.
    *
    * @param src path
    * @param dst path
    * @return F[Unit]
    */
  override def move(src: Path, dst: Path): F[Unit] = {
    F.delay(
      boxFileAtPath(src)
        .foreach(file => {
          val dstPath = splitPath(dst)
          val folder = putFolderAtPath(rootFolder, dstPath._1)
          file.move(folder, dstPath._2)
        })
    )
  }

  /**
    * Copies bytes from srcPath to dstPath. Stores should optimize to use native copy functions to avoid data transfer.
    *
    * @param src path
    * @param dst path
    * @return F[Unit]
    */
  override def copy(src: Path, dst: Path): F[Unit] = {
    F.delay(
      boxFileAtPath(src)
        .foreach(file => {
          val dstPath = splitPath(dst)
          val folder = putFolderAtPath(rootFolder, dstPath._1)
          file.copy(folder, dstPath._2)
        })
    )
  }

  /**
    * Remove byte for given path.
    *
    * @param path to remove
    * @return F[Unit]
    */
  override def remove(path: Path): F[Unit] = {
    F.delay(boxFileAtPath(path).foreach(_.delete()))
  }
}
