package blobstore

import java.nio.charset.Charset
import java.nio.file.Files

import cats.effect.IO
import cats.implicits._
import fs2.Sink
import org.scalatest.{Assertion, FlatSpec, MustMatchers}
import implicits._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global

class StoreOpsTest extends FlatSpec with MustMatchers {

  behavior of "PutOps"
  it should "buffer contents and compute size before calling Store.put" in {
    val bytes: Array[Byte] = "AAAAAAAAAA".getBytes(Charset.forName("utf-8"))
    val store = DummyStore(_.size must be(Some(bytes.length)))

    fs2.Stream.emits(bytes).covary[IO].to(store.bufferedPut(Path("path/to/file.txt"))).compile.drain.unsafeRunSync()
    store.buf.toArray must be(bytes)

  }

  it should "upload a file from a nio Path" in {
    val bytes = "hello".getBytes(Charset.forName("utf-8"))
    val store = DummyStore(_.size must be(Some(bytes.length)))

    fs2.Stream.bracket(IO(Files.createTempFile("test-file", ".bin")))(
      p => {
        fs2.Stream.emits(bytes).covary[IO].to(fs2.io.file.writeAllAsync(p)).drain ++
          fs2.Stream.eval(store.put(p, Path("path/to/file.txt")))
      },
      p => IO(p.toFile.delete).void
    ).compile.drain.unsafeRunSync()
    store.buf.toArray must be(bytes)
  }

}

case class DummyStore(check: Path => Assertion) extends Store[IO] {
  val buf = new ArrayBuffer[Byte]()
  override def put(path: Path): Sink[IO, Byte] = {
    check(path)
    in => {
      buf.appendAll(in.compile.toVector.unsafeRunSync())
      fs2.Stream.emit(())
    }
  }
  override def list(path: Path): fs2.Stream[IO, Path] = ???
  override def get(path: Path, chunkSize: Int): fs2.Stream[IO, Byte] = ???
  override def move(src: Path, dst: Path): IO[Unit] = ???
  override def copy(src: Path, dst: Path): IO[Unit] = ???
  override def remove(path: Path): IO[Unit] = ???
}