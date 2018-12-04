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
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.transfer.{TransferManager, TransferManagerBuilder}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}

class S3StoreTest extends AbstractStoreTest {

  val credentials = new BasicAWSCredentials("my_access_key", "my_secret_key")
  val clientConfiguration = new ClientConfiguration()
  clientConfiguration.setSignerOverride("AWSS3V4SignerType")
  val minioHost: String = Option(System.getenv("BLOBSTORE_MINIO_HOST")).getOrElse("minio-container")
  val minioPort: String = Option(System.getenv("BLOBSTORE_MINIO_PORT")).getOrElse("9000")
  private val client: AmazonS3 = AmazonS3ClientBuilder.standard()
    .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(
      s"http://$minioHost:$minioPort", Regions.US_EAST_1.name()))
    .withPathStyleAccessEnabled(true)
    .withClientConfiguration(clientConfiguration)
    .withCredentials(new AWSStaticCredentialsProvider(credentials))
    .build()
  private val tm: TransferManager = TransferManagerBuilder.standard()
    .withS3Client(client)
    .build()

  override val store: Store[IO] = S3Store[IO](tm, blockingExecutionContext = blockingExecutionContext)
  override val root: String = "blobstore-test-bucket"

  override def beforeAll(): Unit = {
    super.beforeAll()
    try {
      client.createBucket(root)
    } catch {
      case e: com.amazonaws.services.s3.model.AmazonS3Exception if e.getMessage.contains("BucketAlreadyOwnedByYou") =>
        // noop
    }
    ()
  }


  override def afterAll(): Unit = {
    super.afterAll()

    try {
      client.shutdown()
    } catch {
      case _: Throwable =>
    }
  }
}
