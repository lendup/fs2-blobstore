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

import com.jcraft.jsch._
import cats.instances.option._

import scala.util.Try
import java.io.OutputStream

import cats.Traverse
import cats.effect.{Blocker, ConcurrentEffect, ContextShift, IO, Resource}
import cats.effect.concurrent.{MVar, Semaphore}
import fs2.concurrent.Queue

final class SftpStore[F[_]](
  absRoot: String,
  session: Session,
  blocker: Blocker,
  mVar: MVar[F, ChannelSftp],
  semaphore: Option[Semaphore[F]],
  connectTimeout: Int
)(implicit F: ConcurrentEffect[F], CS: ContextShift[F]) extends Store[F] {
  import implicits._

  import Path.SEP

  private val openChannel: F[ChannelSftp] = {
    val openF = blocker.delay{
      val ch = session.openChannel("sftp").asInstanceOf[ChannelSftp]
      ch.connect(connectTimeout)
      ch
    }
    semaphore.fold(openF){s =>
      F.ifM(s.tryAcquire)(openF, getChannel)
    }
  }

  private val getChannel = F.flatMap(mVar.tryTake) {
    case Some(channel) => F.pure(channel)
    case None => openChannel
  }

  private def channelResource: Resource[F, ChannelSftp] = Resource.make{
    getChannel
  }{
    case ch if ch.isClosed => F.unit
    case ch => F.ifM(mVar.tryPut(ch))(F.unit, SftpStore.closeChannel(semaphore, blocker)(ch))
  }

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
    channel <- fs2.Stream.resource(channelResource)
    entry <- q.dequeue.unNoneTerminate.filter(e => e.getFilename != "." && e.getFilename != "..").concurrently(
      fs2.Stream.eval(
        blocker.blockOn(F.flatMap(F.attempt(F.delay(channel.ls(path, entrySelector(e => F.runAsync(q.enqueue1(Some(e)))(_ => IO.unit).unsafeRunSync())))))(_ => q.enqueue1(None)))
      )
    )
  } yield {
    val newPath = if (path.filename == entry.getFilename) path else path / entry.getFilename
    newPath.copy(
      size = Option(entry.getAttrs.getSize),
      isDir = entry.getAttrs.isDir,
      lastModified = Option(entry.getAttrs.getMTime).map(i => new Date(i.toLong * 1000))
    )
  }
}

  override def get(path: Path, chunkSize: Int): fs2.Stream[F, Byte] =
    fs2.Stream.resource(channelResource).flatMap{channel =>
      fs2.io.readInputStream(blocker.delay(channel.get(path)), chunkSize = chunkSize, closeAfterUse = true, blocker = blocker)
    }

  override def put(path: Path): fs2.Pipe[F, Byte, Unit] = { in =>
    def put(channel: ChannelSftp) = F.flatMap(mkdirs(path, channel))(_ => blocker.delay {
      // scalastyle:off
      channel.put(/* dst */ path, /* monitor */ null, /* mode */ ChannelSftp.OVERWRITE, /* offset */ 0)
      // scalastyle:on
    })
    
    def close(os: OutputStream): F[Unit] = blocker.delay(os.close())

    def pull(channel: ChannelSftp): fs2.Stream[F, Unit] = fs2.Stream.bracket(put(channel))(close)
      .flatMap(os => _writeAllToOutputStream1(in, os, blocker).stream)

    fs2.Stream.resource(channelResource).flatMap(channel => pull(channel))
  }

  override def move(src: Path, dst: Path): F[Unit] =
    channelResource.use{channel =>
      F.flatMap(mkdirs(dst, channel))(_ => blocker.delay(channel.rename(src, dst)))
    }

  override def copy(src: Path, dst: Path): F[Unit] = {
    val s = for {
      channel <- fs2.Stream.resource(channelResource)
      _ <- fs2.Stream.eval(F.delay(mkdirs(dst, channel)))
      _ <- get(src).through(this.bufferedPut(dst, blocker))
    } yield ()

    s.compile.drain
  }

  override def remove(path: Path): F[Unit] = channelResource.use{channel =>
    blocker.delay{
      try {
        channel.rm(path)
      } catch {
        // Let the remove() call succeed if there is no file at this path
        case e: SftpException if e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE => ()
      }
    }
  }

  implicit def _pathToString(path: Path): String = s"$absRoot$SEP${path.root}$SEP${path.key}"

  private def mkdirs(path: Path, channel: ChannelSftp): F[Unit] =
    blocker.delay {
      _pathToString(path).split(SEP.toChar).drop(1).foldLeft("") { case (acc, s) =>
        Try(channel.mkdir(acc))
        s"$acc$SEP$s"
      }
      ()
    }

}

object SftpStore {
  /**
    * Safely initialize SftpStore and disconnect ChannelSftp and Session upon finish.
    * @param fa F[ChannelSftp] how to connect to SFTP server
    * @return Stream[ F, SftpStore[F] ] stream with one SftpStore, sftp channel will disconnect once stream is done.
    */
  def apply[F[_]](
    absRoot: String,
    fa: F[Session],
    blocker: Blocker,
    maxChannels: Option[Long] = None,
    connectTimeout: Int = 10000
  )(implicit F: ConcurrentEffect[F], CS: ContextShift[F]): fs2.Stream[F, SftpStore[F]] =
    if (maxChannels.exists(_ < 1)) {
      fs2.Stream.raiseError[F](new IllegalArgumentException(s"maxChannels must be >= 1"))
    } else {
      for {
        session <- fs2.Stream.bracket(fa)(session => F.delay(session.disconnect()))
        semaphore <- fs2.Stream.eval(Traverse[Option].sequence(maxChannels.map(Semaphore.apply[F])))
        mVar <- fs2.Stream.bracket(MVar.empty[F, ChannelSftp])(mVar => F.flatMap(mVar.tryTake)(_.fold(F.unit)(closeChannel[F](semaphore, blocker))))
      } yield new SftpStore[F](absRoot, session, blocker, mVar, semaphore, connectTimeout)
    }

  private def closeChannel[F[_]](semaphore: Option[Semaphore[F]], blocker: Blocker)(ch: ChannelSftp)(implicit F: ConcurrentEffect[F], CS: ContextShift[F]): F[Unit] =
    F.productR(semaphore.fold(F.unit)(_.release))(blocker.delay(ch.disconnect()))
}
