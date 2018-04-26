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
package s3

import cats.effect.IO
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3ClientBuilder

@IntegrationTest
class S3StoreTest extends AbstractStoreTest {

  import scala.concurrent.ExecutionContext.Implicits.global
  private val client = AmazonS3ClientBuilder.standard().withRegion(Regions.US_EAST_1).build()
  override val store: Store[IO] = S3Store[IO](client)
  override val root: String = System.getenv("S3_STORE_TEST_BUCKET")

  override def afterAll(): Unit = {
    super.afterAll()

    try {
      client.shutdown()
    } catch {
      case _: Throwable =>
    }
  }
}
