package blobstore.box


import blobstore.{Path, Store}
import cats.effect.IO
import com.box.sdk.BoxAPIConnection
import org.scalatest.{BeforeAndAfterAll, FlatSpec, MustMatchers}

class BoxStoreTest extends FlatSpec with MustMatchers with BeforeAndAfterAll{

  val myDevToken = "dZbJ469nDKghXQu8cSrPg0rU19KdZM3N"
  val api = new BoxAPIConnection(myDevToken)
  val testEReportsFolder = "48353023088"

  val store: Store[IO] = new BoxStore[IO](api, testEReportsFolder)
  val pathString: String = "2018/04"

  it should "list" in {
//    val allFiles = store.list(Path(pathString)).compile.toList.unsafeRunSync()
//    allFiles.foreach(p => println(p.toString))

    // val allFiles: String = store.getContents(Path("2018/04/actualdata.txt")).unsafeRunSync()
    // println(allFiles)
    // store.put("here is some test string", Path("2018/04/path/to/myfile.txt")).unsafeRunSync()
    store.move(Path("2018/04/actualdata.txt"), Path("2018/04/moveddata.txt")).unsafeRunSync()
  }
}
