package blobstore.gcs

import blobstore.{AbstractStoreTest, Store}
import cats.effect.IO
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper

class GcsStoreTest extends AbstractStoreTest {
  override val store: Store[IO] = new GcsStore[IO](LocalStorageHelper.getOptions.getService, blocker)
  override val root: String = "bucket"
}
