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

import java.util.Date

import cats.effect.{ConcurrentEffect, ContextShift, IO}
import com.jcraft.jsch._
import fs2.concurrent.Queue
import scala.util.Try
import scala.concurrent.ExecutionContext
import java.io.OutputStream

final case class SftpStore[F[_]](absRoot: String, channel: ChannelSftp, blockingExecutionContext: ExecutionContext)(implicit F: ConcurrentEffect[F], CS: ContextShift[F])
  extends Store[F] {
  import implicits._

  import Path.SEP

  /**
    * @param path path to list
    * @return Stream[F, Path]
    */
  override def list(path: Path): fs2.Stream[F, Path] = {

    def entrySelector(cb: ChannelSftp#LsEntry => Unit): ChannelSftp.LsEntrySelector = new ChannelSftp.LsEntrySelector{
      def select(entry: ChannelSftp#LsEntry): Int = {
        cb(entry)
        ChannelSftp.LsEntrySelector.CONTINUE
      }
    }

  for{
    q <- fs2.Stream.eval(Queue.bounded[F, Option[ChannelSftp#LsEntry]](64))
    _ <- fs2.Stream.eval(
      CS.evalOn(blockingExecutionContext)(F.flatMap(F.attempt(F.delay(channel.ls(path, entrySelector(e => F.runAsync(q.enqueue1(Some(e)))(_ => IO.unit).unsafeRunSync())))))(_ => q.enqueue1(None)))
    )
    entry <- q.dequeue.unNoneTerminate.filter(e => e.getFilename != "." && e.getFilename != "..")
  } yield {
    val newPath = if (path.filename == entry.getFilename) path else path / entry.getFilename
    newPath.copy(
      size = Option(entry.getAttrs.getSize),
      isDir = entry.getAttrs.isDir,
      lastModified = Option(entry.getAttrs.getMTime).map(i => new Date(i.toLong * 1000))
    )
  }
}

  override def get(path: Path, chunkSize: Int): fs2.Stream[F, Byte] = {
    fs2.io.readInputStream(CS.evalOn(blockingExecutionContext)(F.delay(channel.get(path))), chunkSize = chunkSize, closeAfterUse = true, blockingExecutionContext = blockingExecutionContext)
  }

  override def put(path: Path): fs2.Sink[F, Byte] = { in =>
    val put = F.flatMap(mkdirs(path))(_ => CS.evalOn(blockingExecutionContext)(F.delay {
      // scalastyle:off
      channel.put(/* dst */ path, /* monitor */ null, /* mode */ ChannelSftp.OVERWRITE, /* offset */ 0)
      // scalastyle:on
    }))
    
    def close(os: OutputStream): F[Unit] = CS.evalOn(blockingExecutionContext)(F.delay(os.close()))

    val pull = for {
      os <- fs2.Pull.acquireCancellable(put)(ch => close(ch))
      _ <- _writeAllToOutputStream1(in, os.resource, blockingExecutionContext)
    } yield ()

    pull.stream
  }

  override def move(src: Path, dst: Path): F[Unit] = F.flatMap(mkdirs(dst))(_ => CS.evalOn(blockingExecutionContext)(F.delay {
    channel.rename(src, dst)
  }))

  override def copy(src: Path, dst: Path): F[Unit] = {
    val s = for {
      _ <- fs2.Stream.eval(F.delay(mkdirs(dst)))
      _ <- get(src).to(this.bufferedPut(dst, blockingExecutionContext))
    } yield ()

    s.compile.drain
  }

  override def remove(path: Path): F[Unit] = CS.evalOn(blockingExecutionContext)(F.delay{
    try {
      channel.rm(path)
    } catch {
      // Let the remove() call succeed if there is no file at this path
      case e: SftpException if e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE => ()
    }
  })

  implicit def _pathToString(path: Path): String = s"$absRoot$SEP${path.root}$SEP${path.key}"

  private def mkdirs(path: Path): F[Unit] = CS.evalOn(blockingExecutionContext) {
    F.delay {
      _pathToString(path).split(SEP.toChar).drop(1).foldLeft("") { case (acc, s) =>
        Try(channel.mkdir(acc))
        s"$acc$SEP$s"
      }
      ()
    }
  }
}

object SftpStore {
  /**
    * Safely initialize SftpStore and disconnect ChannelSftp and Session upon finish.
    * @param fa F[ChannelSftp] how to connect to SFTP server
    * @return Stream[ F, SftpStore[F] ] stream with one SftpStore, sftp channel will disconnect once stream is done.
    */

  def apply[F[_]](absRoot: String, fa: F[ChannelSftp], blockingExecutionContext: ExecutionContext)(implicit F: ConcurrentEffect[F], CS: ContextShift[F])
  : fs2.Stream[F, SftpStore[F]] = {
    fs2.Stream.bracket(fa)(
      release = channel => F.delay { channel.disconnect() ; channel.getSession.disconnect() }
    ).flatMap { channel =>
      fs2.Stream.eval(F.delay(SftpStore[F](absRoot, channel, blockingExecutionContext)))
    }
  }
}
