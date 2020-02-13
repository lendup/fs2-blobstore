package blobstore.gcs

import java.io.InputStream
import java.nio.channels.Channels
import java.time.Instant
import java.util.Date

import blobstore.{Path, Store}
import cats.effect.{Blocker, ContextShift, Sync}
import com.google.api.gax.paging.Page
import com.google.cloud.storage.{Acl, Blob, BlobId, BlobInfo, Storage, StorageException}
import com.google.cloud.storage.Storage.{BlobListOption, CopyRequest}
import fs2.{Chunk, Pipe, Stream}

import scala.jdk.CollectionConverters._

final class GcsStore[F[_]](storage: Storage, blocker: Blocker, acls: List[Acl] = Nil)(implicit F: Sync[F], CS: ContextShift[F]) extends Store[F] {

  private def _chunk(pg: Page[Blob]): Chunk[Path] = {
    val (dirs, files) = pg.getValues.asScala.toSeq.partition(_.isDirectory)
    val dirPaths = Chunk.seq(dirs.map(b => Path(root = b.getBucket, key = b.getName.stripSuffix("/"), size = None, isDir = true, lastModified = None)))
    val filePaths = Chunk.seq(files.map{b =>
      val size = Option(b.getSize: java.lang.Long).map(_.toLong) // Prevent throwing NPE (see https://github.com/scala/bug/issues/9634)
      val lastModified = Option(b.getUpdateTime: java.lang.Long).map(millis => Date.from(Instant.ofEpochMilli(millis))) // Prevent throwing NPE (see https://github.com/scala/bug/issues/9634)
      Path(b.getBucket, key = b.getName, size = size, isDir = false, lastModified = lastModified)
    })
    Chunk.concat(List(dirPaths, filePaths))
  }

  def list(path: Path): fs2.Stream[F, Path] = {
    Stream.unfoldChunkEval[F, () => Option[Page[Blob]], Path]{
      () => Some(storage.list(path.root, BlobListOption.currentDirectory(), BlobListOption.prefix(path.key)))
    }{getPage =>
      blocker.delay{
        getPage().map{pg =>
          if (pg.hasNextPage){
            (_chunk(pg), () => Some(pg.getNextPage))
          } else {
            (_chunk(pg), () => None)
          }
        }
      }
    }
  }

  def get(path: Path, chunkSize: Int): fs2.Stream[F, Byte] = {
    val is = blocker.delay(Channels.newInputStream(storage.get(path.root, path.key).reader()))
    fs2.io.readInputStream(is, chunkSize, blocker, closeAfterUse = true)
  }

  def put(path: Path): Pipe[F, Byte, Unit] = {
    val fos = Sync[F].delay{
      val builder = {
        val b = BlobInfo.newBuilder(path.root, path.key)
        if (acls.nonEmpty) b.setAcl(acls.asJava) else b
      }
      val blobInfo = builder.build()
      val writer = storage.writer(blobInfo)
      Channels.newOutputStream(writer)
    }
    fs2.io.writeOutputStream(fos, blocker, closeAfterUse = true)
  }

  def move(src: Path, dst: Path): F[Unit] = F.productR(copy(src, dst))(remove(src))

  def copy(src: Path, dst: Path): F[Unit] = {
    val req = CopyRequest.newBuilder().setSource(src.root, src.key).setTarget(BlobId.of(dst.root, dst.key)).build()
    F.void(blocker.delay(storage.copy(req).getResult))
  }

  def remove(path: Path): F[Unit] =
    F.void(blocker.delay(storage.delete(path.root, path.key)))
}


object GcsStore{
  def apply[F[_]](
    storage: Storage,
    blocker: Blocker,
    acls: List[Acl]
  )(implicit F: Sync[F], CS: ContextShift[F]): GcsStore[F] = new GcsStore(storage, blocker, acls)
}