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

import cats.effect.{Effect, Sync}
import cats.implicits._
import fs2.{Pipe, Pull, Stream}

import scala.concurrent.ExecutionContext

package object blobstore {
  protected[blobstore] def _writeAllToOutputStream1[F[_]](in: Stream[F, Byte], out: OutputStream)(
    implicit F: Effect[F]): Pull[F, Nothing, Unit] = {
    in.pull.unconsChunk.flatMap {
      case None => Pull.done
      case Some((hd, tl)) => Pull.eval[F, Unit](F.delay(out.write(hd.toArray))) >> _writeAllToOutputStream1(tl, out)
    }
  }

  protected[blobstore] def bufferToDisk[F[_]: Effect](chunkSize: Int)(implicit ec: ExecutionContext)
  : Pipe[F, Byte, (Long, Stream[F, Byte])] = {
    in => Stream.bracket(Sync[F].delay(Files.createTempFile("bufferToDisk", ".bin")))(
      p => {
        in.to(fs2.io.file.writeAllAsync(p)).drain ++
          Stream.emit((p.toFile.length, fs2.io.file.readAllAsync(p, chunkSize)))
      },
      p => Sync[F].delay(p.toFile.delete).void
    )
  }

}
