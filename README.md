# fs2-blobstore

[![Build Status](https://travis-ci.org/lendup/fs2-blobstore.svg?branch=master)](https://travis-ci.org/lendup/fs2-blobstore)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.lendup.fs2-blobstore/core_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.lendup.fs2-blobstore/core)
[![codecov](https://codecov.io/gh/lendup/fs2-blobstore/branch/master/graph/badge.svg)](https://codecov.io/gh/lendup/fs2-blobstore)
[![Join the chat at https://gitter.im/fs2-blobstore/Lobby](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/fs2-blobstore/Lobby)


Minimal, idiomatic, stream-based Scala interface for key/value store implementations.
It provides abstractions for S3-like key/value store backed by different persistence 
mechanisms (i.e. S3, FileSystem, sftp, etc).

### Installing

fs2-blobstore is deployed to maven central, add to build.sbt:

```sbtshell
libraryDependencies ++= Seq(
  "com.lendup.fs2-blobstore" %% "core" % "0.1.3",
  "com.lendup.fs2-blobstore" %% "sftp" % "0.1.3",
  "com.lendup.fs2-blobstore" %% "s3" % "0.1.3"
)
```

`core` module has minimal dependencies and only provides `FileStore` implementation.
`sftp` module provides `SftpStore` and depends on [Jsch client](http://www.jcraft.com/jsch/). 
`s3` module provides `S3Store` and depends on [AWS S3 SDK](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/examples-s3.html)


### Store Abstraction

The goal of the [Store interface](core/src/main/scala/blobstore/Store.scala) is to 
have a common representation of key/value functionality (get, put, list, etc) as 
streams that can be composed, transformed and piped just like any other `fs2.Stream` 
or `fs2.Sink` regardless of the underlying storage mechanism.

This is especially useful for unit testing if you are building a S3 or SFTP backed 
system as you can provide a filesystem based implementation for tests that is 
guaranteed to work the same way as you production environment.

The three main activities in a key/value store are modeled like:

 ```scala
 def list(path: Path): fs2.Stream[F, Path]
 def get(path: Path, chunkSize: Int): fs2.Stream[F, Byte]
 def put(path: Path, contentLength: Long): fs2.Sink[F, Byte]  
 ```  

Note that `list` and `get` are modeled as streams since they are reading 
(potentially) very large amounts of data from storage, while `put` is 
represented as a sink of byte so that any stream of bytes can by piped 
into it to upload data to storage.

### Implicit Ops

`import blobstore.implicits._`

[StoreOps](core/src/main/scala/blobstore/StoreOps.scala) and 
[PathOps](core/src/main/scala/blobstore/Path.scala) provide functionality on 
both `Store` and `Path` for commonly performed tasks (i.e. upload/download a 
file from/to local filesystem, collect all returned paths when listing, composing 
paths or extracting filename of the path).

Most of these common tasks encapsulate stream manipulation and provide a simpler 
interface that return the corresponding effect monad.  These are also very good 
examples of how to use blobstore streams and sink in different scenarios.

### Tests

All store implementations must support and pass the suite of tests in 
[AbstractStoreTest](core/src/test/scala/blobstore/AbstractStoreTest.scala). 
It is expected that each store implementation (like s3, sftp, file) should 
contain the `Store` implementation and at least one test suite that inherits 
from `AbstractStoreTest` and overrides store and root attributes:

```scala
class MyStoreImplTest extends blobstore.AbstractStoreTest {
  override val store: blobstore.Store[cats.effect.IO] = MyStoreImpl( ... )
  override val root: String = "my_store_impl_tests"
}
```  

This test suite will guarantee that basic operations are supported properly and 
consistent with all other `Store` implementations.

**Running Tests:**

Tests for core module have no dependencies and can be run with `sbt core/test`.
These include `FileStore` and `Path` tests.

Currently, tests for `SftpStore` and `S3Store` are annotated with `org.scalatest.Ignore`
because they require a running SFTP server and AWS bucket and credentials respectively.
To run either one of these tests locally please remove annotation but make sure you do
not push this change as it will make tests fail in travis (we are working to get these
tests set up to run in ci pipeline soon).

To run `S3StoreTest` locally you need to provide a bucket with write access and configure
AWS credentials per [default credentials chain](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html).

```bash
S3_STORE_TEST_BUCKET=your-bucket sbt s3/test
```

To run `SftpStoreTest` it is required to set up a local SFTP server that:

1. Bound to `127.0.0.1` on standard SSH port 22 (mac or linux users can enable SSH server)
1. Server public key is listed in `~/.ssh/known_hosts`
1. Server recognizes current user and public key included in authorized users
1. Corresponding private key is stored in `~/.ssh/id_rsa_tmp`

```bash
sbt sftp/test
```


### Path Abstraction

`blobstore.Path` is the representation of `key` in the key/value store. The key 
representation is based on S3 that has a `root` (or bucket) and a `key` string.

When functions in the `Store` interface that receive a `Path` should assume that only
root and key values are set, there is no guarantee that the other attributes of `Path`
would be filled: size, isDir, lastModified. On the other hand, when a `Store` implements
the list function, it is expected that all 3 fields will be present in the response.

By importing implicit `PathOps` into the scope you can make use of path composition `/`
and `filename` function that returns the substring of the path's key after the last path
separator.

**NOTE:** a good improvement to the path abstraction would be to handle OS specific 
separators when referring to filesystem paths.

### Store Implementations

   * [FileStore](fs/src/main/scala/blobstore/fs/FileStore.scala) backed by local
   FileSystem. FileStore is provided as part of core module because it doesn't
   include any additional dependencies and it is used as the default source store
   in TransferOps tests. It only requires root path in the local file system:
     ```scala
     import blobstore.Store, blobstore.fs.FileStore 
     import java.nio.file.Paths
     import cats.effect.IO 
     val store: Store[IO] = FileStore[IO](Paths.get("tmp/"))
     ```
   * [S3Store](s3/src/main/scala/blobstore/s3/S3Store.scala) backed by 
   [AWS S3](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/examples-s3.html). 
   It requires an authenticated `AmazonS3` client:
     ```scala
     import blobstore.Store, blobstore.s3.S3Store
     import com.amazonaws.services.s3.AmazonS3ClientBuilder
     import cats.effect.IO
     val store: Store[IO] = S3Store[IO](AmazonS3ClientBuilder.standard().build())
     ```
   * [SftpStore](sftp/src/main/scala/blobstore/sftp/SftpStore.scala) backed by 
   SFTP server with [Jsch client](http://www.jcraft.com/jsch/). It requires a 
   connected `ChannelSftp`:
     ```scala
     import blobstore.Store, blobstore.sftp.SftpStore
     import com.jcraft.jsch.{ChannelSftp, JSch}
     import cats.effect.IO

     val jsch = new JSch()
     val session = jsch.getSession("sftp.domain.com")
     session.connect()
 
     val channel = session.openChannel("sftp").asInstanceOf[ChannelSftp]
     channel.connect(5000)

     val store: Store[IO] = SftpStore("root/server/path", channel)
     ``` 

