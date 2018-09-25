package blobstore
package box

import cats.effect.IO
import com.box.sdk.BoxAPIConnection

/**
  * Run these with extreme caution. If configured properly, this test as will attempt to write to your Box server.
  * See AbstractStoreTest to see what operations performed here.
  */
@IntegrationTest
class BoxStoreIntegrationTest extends AbstractStoreTest {

  // Your Box dev token. You are free to use a different way of instantiating your BoxAPIConnection,
  // but be careful not to commit this information.
  val boxDevToken = System.getenv("BOX_TEST_BOX_DEV_TOKEN")

  // The root folder you want to operate under.
  // Ideally this should be an isolated test folder that does not contain important files.
  // You may risk overwriting or deleting files if you are running this on an existing directory.
  val rootFolderId = System.getenv("BOX_TEST_ROOT_FOLDER_ID")

  // This test simply uses a dev token to start a connection, but you can replace this with any BoxAPIConnection.
  val api = new BoxAPIConnection(boxDevToken)

  override val store: Store[IO] = new BoxStore[IO](api, rootFolderId, blockingExecutionContext)

  // If your rootFolderId is a safe directory to test under, this root string doesn't matter that much.
  override val root: String = "BoxStoreTest"
}
