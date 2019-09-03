v0.5.0
======
* Add GcsStore (Google Cloud storage) [#46](https://github.com/lendup/fs2-blobstore/pull/46)

v0.4.3
======
* Fix BoxStore list method which was broken for directories in the root level

v0.4.2
======
* Add test to ensure remove() does not fail for nonexistent paths
* Fix remove() in FileStore, SftpStore, to meet the above requirement

v0.4.1
======
* Fixed path extension to work on a path that is created with just a bucket and no key [#37](https://github.com/lendup/fs2-blobstore/pull/37)

v0.4.0
======
* Make object ACLs configurable for S3Store

v0.3.1
======
* Update S3Store to use other TransferManager methods when possible

v0.3.0
======
* Update S3Store to use s3's TransferManager.upload during put [#28](https://github.com/lendup/fs2-blobstore/pull/28)

v0.2.2
======
* Update fs2 to 1.0.0, Scala to 2.12.7 [#24](https://github.com/lendup/fs2-blobstore/pull/24)


v0.2.1
======
* Update to fs2 1.0.0-M5 [#23](https://github.com/lendup/fs2-blobstore/pull/23)


v0.1.7 and earlier
==================

* Initial definition of `Store` and implementations for S3, SFTP, Filesystem, and Box stores.
* Bug fixes
* fs2 0.10.4
