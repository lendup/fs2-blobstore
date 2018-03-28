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
package sftp

import java.nio.file.Files
import java.util.Date

import scala.collection.JavaConverters._
import cats.effect.Effect
import com.jcraft.jsch._

import scala.util.Try
import implicits._

case class SftpStore[F[_]](absRoot: String, channel: ChannelSftp)(implicit F: Effect[F]) extends Store[F] {
  import Path.SEP

  /**
    * TODO: This is an unsafe list method since it uses ChannelSftp.ls(String) which loads the entire result in memory.
    *       A better approach would be to use ChannelSftp.ls(String, LsEntrySelector) and provide a function that is
    *       called with every LSEntry returned, this is how ChannelSftp.ls is defined:
    *
    *         public java.util.Vector ls(String path) throws SftpException{
    *           final java.util.Vector v = new Vector();
    *           LsEntrySelector selector = new LsEntrySelector(){
    *             public int select(LsEntry entry){
    *               v.addElement(entry);
    *               return CONTINUE;
    *             }
    *           };
    *           ls(path, selector);
    *           return v;
    *         }
    *
    * @param path path to list
    * @return Stream[F, Path]
    */
  override def list(path: Path): fs2.Stream[F, Path] = {
    val l: F[Seq[Path]] = F.delay {
      Try(channel.ls(path).asScala.toList).getOrElse(List.empty)
        .map(_.asInstanceOf[ChannelSftp#LsEntry])
        .filterNot(e => e.getFilename == "." || e.getFilename == "..")
        .map { entry =>
          val newPath = if (path.filename == entry.getFilename) path else path / entry.getFilename
          newPath.copy(
            size = Option(entry.getAttrs.getSize),
            isDir = entry.getAttrs.isDir,
            lastModified = Option(entry.getAttrs.getMTime).map(i => new Date(i.toLong * 1000))
          )
        }
    }
    fs2.Stream.eval(l).flatMap(x => fs2.Stream.emits(x))
  }

  override def get(path: Path, chunkSize: Int): fs2.Stream[F, Byte] = {
    fs2.io.readInputStream(F.delay(channel.get(path)), chunkSize = chunkSize, closeAfterUse = true)
  }

  override def put(path: Path): fs2.Sink[F, Byte] = { in =>
    val put = F.delay {
      _pathToString(path).split(SEP.toChar).drop(1).foldLeft("") { case (acc, s) =>
        Try(channel.mkdir(acc))
        s"$acc$SEP$s"
      }

      // scalastyle:off
      channel.put(/* dst */ path, /* monitor */ null, /* mode */ ChannelSftp.OVERWRITE, /* offset */ 0)
      // scalastyle:on
    }
    val pull = for {
      os <- fs2.Pull.acquireCancellable(put)(ch => F.delay(ch.close()))
      _ <- _writeAllToOutputStream1(in, os.resource)
    } yield ()

    pull.stream
  }

  override def move(src: Path, dst: Path): F[Unit] = F.delay(channel.rename(src, dst))

  override def copy(src: Path, dst: Path): F[Unit] = {
    val cp = for {
      tmp <- fs2.Stream.eval(F.delay(Files.createTempFile("sftp-copy", ".tmp")))
      _ <- (get(src) to fs2.io.file.writeAll(tmp)).last
      _ <- (fs2.io.file.readAll(tmp, 4096) to put(dst)).last
      _ <- fs2.Stream.eval(F.delay(Files.deleteIfExists(tmp))).handleErrorWith(_ => fs2.Stream.empty)
    } yield ()

    cp.compile.drain
  }

  override def remove(path: Path): F[Unit] = F.delay(channel.rm(path))

  implicit def _pathToString(path: Path): String = s"$absRoot$SEP${path.root}$SEP${path.key}"
}

object SftpStore {
  /**
    * Safely initialize SftpStore and disconnect ChannelSftp and Session upon finish.
    * @param fa F[ChannelSftp] how to connect to SFTP server
    * @return Stream[ F, SftpStore[F] ] stream with one SftpStore, sftp channel will disconnect once stream is done.
    */
  def apply[F[_]](absRoot: String, fa: F[ChannelSftp])(implicit F: Effect[F]): fs2.Stream[F, SftpStore[F]] = {
    fs2.Stream.bracket(fa)(
      channel => fs2.Stream.eval(F.delay(SftpStore[F](absRoot, channel))),          // consume
      channel => F.delay { channel.disconnect() ; channel.getSession.disconnect() } // shutdown
    )
  }
}