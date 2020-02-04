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

import java.nio.file.Paths
import java.util.Properties

import cats.effect.IO
import cats.effect.concurrent.MVar
import com.jcraft.jsch.{ChannelSftp, JSch}

class SftpStoreTest extends AbstractStoreTest {

  val session = try {
    val jsch = new JSch()

    val session = jsch.getSession("blob", "sftp-container", 22)
    session.setTimeout(10000)
    session.setPassword("password")

    val config = new Properties
    config.put("StrictHostKeyChecking", "no")
    session.setConfig(config)

    session.connect()

    session
  } catch {
    // this is UGLY!!! but just want to ignore errors if you don't have sftp container running
    case e: Throwable =>
      e.printStackTrace()
      null
  }

  private val rootDir = Paths.get("tmp/sftp-store-root/").toAbsolutePath.normalize
  val mVar = MVar.empty[IO, ChannelSftp].unsafeRunSync()
  override val store: Store[IO] = new SftpStore[IO]("/", session, blocker, mVar, None, 10000)
  override val root: String = "sftp_tests"

  // remove dirs created by AbstractStoreTest
  override def afterAll(): Unit = {
    super.afterAll()

    try {
      session.disconnect()
    } catch {
      case _: Throwable =>
    }

    cleanup(rootDir.resolve(s"$root/test-$testRun"))

  }

  it should "list more than 64 (default queue/buffer size) keys" in {
    import cats.implicits._
    import blobstore.implicits._

    val dir: Path = dirPath("list-more-than-64")

    val paths = (1 to 256)
      .toList
      .map(i => s"filename-$i.txt")
      .map(writeFile(store, dir))

    val exp = paths.map(_.key).toSet

    store.listAll(dir).unsafeRunSync().map(_.key).toSet must be(exp)

    val io: IO[List[Unit]] = paths.map(store.remove).sequence
    io.unsafeRunSync()

    store.listAll(dir).unsafeRunSync().isEmpty must be(true)
  }
}
