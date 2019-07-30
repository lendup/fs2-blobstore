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

import blobstore.PathOps._
import org.scalatest.MustMatchers
import org.scalatest.flatspec.AnyFlatSpec

class PathTest extends AnyFlatSpec with MustMatchers {
  behavior of "Path"
  it should "parse string path to file correctly" in {
    val path = Path("s3://some-bucket/path/to/file")
    path must be(Path("some-bucket", "path/to/file", None, false, None))
  }

  it should "parse string path to file without prefix correctly" in {
    val path = Path("some-bucket/path/to/file")
    path must be(Path("some-bucket", "path/to/file", None, false, None))
  }

  it should "parse string path to file stating with / correctly" in {
    val path = Path("/some-bucket/path/to/file")
    path must be(Path("some-bucket", "path/to/file", None, false, None))
  }

  it should "parse string path to dir correctly" in {
    val path = Path("s3://some-bucket/path/to/")
    path must be(Path("some-bucket", "path/to/", None, true, None))
  }

  it should "parse paths with no key" in {
    val path = Path("s3://some-bucket")
    path must be(Path("some-bucket", "", None, false, None))
  }

  it should "extend a path with no key correctly" in {
    val path = Path("some-bucket") / "key"

    path must be(Path("some-bucket", "key", None, false, None))
  }
}