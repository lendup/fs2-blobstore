package blobstore.box

import blobstore.{Path, Store}
import cats.effect.IO
import com.box.sdk.BoxAPIConnection
import org.scalatest.{BeforeAndAfterAll, FlatSpec, MustMatchers}
import blobstore.implicits._

class BoxStoreTest extends FlatSpec with MustMatchers with BeforeAndAfterAll{

  val myDevToken = "ugVpdce90y4Hv59oDOmrTNmUdXm9FjKe"
  val api = new BoxAPIConnection(myDevToken)
  val testEReportsFolder = "48353023088"

  val store: Store[IO] = new BoxStore[IO](api, testEReportsFolder)
  val pathString: String = "48417382875/2018/04"

  it should "list" in {
//    val allFiles = store.list(Path(pathString)).compile.toList.unsafeRunSync()
//    allFiles.foreach(p => println(p.toString))

    val allFiles: String = store.getContents(Path("2018/04/actualdata.txt")).unsafeRunSync()
    println(allFiles)
  }
}
