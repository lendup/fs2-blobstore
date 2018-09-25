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
import com.jcraft.jsch.{ChannelSftp, JSch}

class SftpStoreTest extends AbstractStoreTest {

  val (channel, session) = try {
    val jsch = new JSch()

    val session = jsch.getSession("blob", "sftp-container", 22)
    session.setTimeout(10000)
    session.setPassword("password")

    val config = new Properties
    config.put("StrictHostKeyChecking", "no")
    session.setConfig(config)

    session.connect()

    val channel = session.openChannel("sftp").asInstanceOf[ChannelSftp]
    channel.connect(10000)
    (channel, session)
  } catch {
    // this is UGLY!!! but just want to ignore errors if you don't have sftp container running
    case e: Throwable =>
      e.printStackTrace()
      (null, null)
  }

  private val rootDir = Paths.get("tmp/sftp-store-root/").toAbsolutePath.normalize
  override val store: Store[IO] = SftpStore[IO]("/", channel, blockingExecutionContext = blockingExecutionContext)
  override val root: String = "sftp_tests"

  // remove dirs created by AbstractStoreTest
  override def afterAll(): Unit = {
    super.afterAll()

    try {
      channel.disconnect()
      session.disconnect()
    } catch {
      case _: Throwable =>
    }

    cleanup(rootDir.resolve(s"$root/test-$testRun"))

  }

}
