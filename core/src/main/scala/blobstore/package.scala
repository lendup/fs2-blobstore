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

import java.io.OutputStream
import java.nio.file.Files

import cats.effect.{ContextShift, Sync, Blocker}
import fs2.{Pipe, Pull, Stream}
import cats.implicits._

package object blobstore {
  protected[blobstore] def _writeAllToOutputStream1[F[_]](in: Stream[F, Byte], out: OutputStream, blocker: Blocker)(
    implicit F: Sync[F], CS: ContextShift[F]): Pull[F, Nothing, Unit] = {
    in.pull.uncons.flatMap {
      case None => Pull.done
      case Some((hd, tl)) => Pull.eval[F, Unit](blocker.delay(out.write(hd.toArray))) >> _writeAllToOutputStream1(tl, out, blocker)
    }
  }

  protected[blobstore] def bufferToDisk[F[_]](chunkSize: Int, blocker: Blocker)(implicit F: Sync[F], CS: ContextShift[F])
  : Pipe[F, Byte, (Long, Stream[F, Byte])] = {
    in => Stream.bracket(F.delay(Files.createTempFile("bufferToDisk", ".bin")))(
      p => F.delay(p.toFile.delete).void).flatMap { p =>
        in.through(fs2.io.file.writeAll(p, blocker)).drain ++
        Stream.emit((p.toFile.length, fs2.io.file.readAll(p, blocker, chunkSize)))
    }
  }

}
