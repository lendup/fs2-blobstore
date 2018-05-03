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

import java.io.{InputStream, PipedInputStream, PipedOutputStream}

import cats.effect.Effect
import cats.syntax.functor._
import cats.syntax.flatMap._
import fs2.{Segment, Sink, Stream}

import scala.collection.JavaConverters._
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.services.s3.model.{CopyObjectRequest, ListObjectsRequest, ObjectListing, ObjectMetadata}

import scala.concurrent.ExecutionContext

case class S3Store[F[_]](s3: AmazonS3, sse: Option[String] = None)(implicit F: Effect[F], ec: ExecutionContext) extends Store[F] {

  // convert ObjectListing to Segment[Path, Unit]
  private def _segment(ol: ObjectListing): Segment[Path, Unit] = {
    val dirs: Segment[Path, Unit] = Segment.seq(ol.getCommonPrefixes.asScala.map(s =>
      Path(ol.getBucketName, s.dropRight(1), None, true, None)
    ))
    val files: Segment[Path, Unit] = Segment.seq(ol.getObjectSummaries.asScala.map(o =>
      Path(o.getBucketName, o.getKey, Option(o.getSize), isDir = false, Option(o.getLastModified))
    ))

    dirs ++ files
  }

  override def list(path: Path): Stream[F, Path] = {
    Stream.unfoldSegmentEval[F, () => Option[ObjectListing], Path] {
      // State type is a function that can provide next ObjectListing
      // initially we get it from listObjects, subsequent iterations get it from listNextBatchOfObjects
      () => Option(s3.listObjects(new ListObjectsRequest(path.root, path.key, "", Path.SEP.toString, 1000)))
    }{
      // to unfold the stream we need to emit a Segment for the current ObjectListing received from state function
      // if returned ObjectListing is not truncated we emit the last Segment and set up state function to return None
      // and stop unfolding in the next iteration
      getObjectListing => F.delay {
        getObjectListing().map { ol =>
          if (ol.isTruncated)
            (_segment(ol), () => Option(s3.listNextBatchOfObjects(ol)))
          else
            (_segment(ol), () => None)
        }
      }
    }
  }

  override def get(path: Path, chunkSize: Int): Stream[F, Byte] = {
    val is: F[InputStream] = F.delay(s3.getObject(path.root, path.key).getObjectContent)
    fs2.io.readInputStream(is, chunkSize, closeAfterUse = true)
  }

  override def put(path: Path): Sink[F, Byte] = { in =>
    val init: F[(PipedOutputStream, PipedInputStream)] = F.delay {
      val os = new PipedOutputStream()
      val is = new PipedInputStream(os)
      (os, is)
    }

    val consume: ((PipedOutputStream, PipedInputStream)) => Stream[F, Unit] = ios => {
      val putToS3 = Stream.eval(F.delay {
        val meta = new ObjectMetadata()
        path.size.foreach(meta.setContentLength)
        sse.foreach(meta.setSSEAlgorithm)
        s3.putObject(path.root, path.key, ios._2, meta)
        ()
      })

      val writeBytes: Stream[F, Unit] =
        _writeAllToOutputStream1(in, ios._1).stream ++ Stream.eval(F.delay(ios._1.close()))

      putToS3 concurrently writeBytes
    }

    val release: ((PipedOutputStream, PipedInputStream)) => F[Unit] = ios => F.delay {
      ios._2.close()
      ios._1.close()
    }

    Stream.bracket(init)(consume, release)
  }

  override def move(src: Path, dst: Path): F[Unit] = for {
    _ <- copy(src, dst)
    _ <- remove(src)
  } yield ()

  override def copy(src: Path, dst: Path): F[Unit] = F.delay {
    val meta = new ObjectMetadata()
    sse.foreach(meta.setSSEAlgorithm)
    val req = new CopyObjectRequest(src.root, src.key, dst.root, dst.key).withNewObjectMetadata(meta)
    s3.copyObject(req)
  }.void

  override def remove(path: Path): F[Unit] = F.delay(s3.deleteObject(path.root, path.key))
}

object S3Store {
  /**
    * Safely initialize S3Store and shutdown Amazon S3 client upon finish.
    *
    * @param fa  F[AmazonS3] how to connect AWS S3 client
    * @param sse Boolean true to force all writes to use SSE algorithm ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION
    * @return Stream[ F, S3Store[F] ] stream with one S3Store, AmazonS3 client will disconnect once stream is done.
    */
  def apply[F[_]](fa: F[AmazonS3], sse: Boolean)(implicit F: Effect[F], ec: ExecutionContext): Stream[F, S3Store[F]] = {
    val opt = if (sse) Option(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION) else None
    apply(fa, opt)
  }

  /**
    * Safely initialize S3Store using AmazonS3ClientBuilder.standard() and shutdown client upon finish.
    *
    * NOTICE: Standard S3 client builder uses the Default Credential Provider Chain, see docs on how to authenticate:
    *         https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html
    *
    * @param sse Boolean true to force all writes to use SSE algorithm ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION
    * @return Stream[ F, S3Store[F] ] stream with one S3Store, AmazonS3 client will disconnect once stream is done.
    */
  def apply[F[_]](sse: Boolean)(implicit F: Effect[F], ec: ExecutionContext): Stream[F, S3Store[F]] = {
    val opt = if (sse) Option(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION) else None
    apply(F.delay(AmazonS3ClientBuilder.standard().build()), opt)
  }

  /**
    * Safely initialize S3Store using AmazonS3ClientBuilder.standard() and shutdown client upon finish.
    *
    * NOTICE: Standard S3 client builder uses the Default Credential Provider Chain, see docs on how to authenticate:
    *         https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html
    *
    * @param sse Boolean true to force all writes to use SSE algorithm ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION
    * @return Stream[ F, S3Store[F] ] stream with one S3Store, AmazonS3 client will disconnect once stream is done.
    */
  def apply[F[_]](sse: String)(implicit F: Effect[F], ec: ExecutionContext): Stream[F, S3Store[F]] =
    apply(F.delay(AmazonS3ClientBuilder.standard().build()), Option(sse))


  /**
    * Safely initialize S3Store using AmazonS3ClientBuilder.standard() and shutdown client upon finish.
    *
    * NOTICE: Standard S3 client builder uses the Default Credential Provider Chain, see docs on how to authenticate:
    *         https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html
    *
    * @return Stream[ F, S3Store[F] ] stream with one S3Store, AmazonS3 client will disconnect once stream is done.
    */
  def apply[F[_]]()(implicit F: Effect[F], ec: ExecutionContext): Stream[F, S3Store[F]] =
    apply(F.delay(AmazonS3ClientBuilder.standard().build()), None)



  /**
    * Safely initialize S3Store and shutdown Amazon S3 client upon finish.
    *
    * @param fa F[AmazonS3] how to connect AWS S3 client
    * @param sse Option[String] sse algorithm
    * @return Stream[ F, S3Store[F] ] stream with one S3Store, AmazonS3 client will disconnect once stream is done.
    */
  def apply[F[_]](fa: F[AmazonS3], sse: Option[String])(implicit F: Effect[F], ec: ExecutionContext)
  : Stream[F, S3Store[F]] = {
    fs2.Stream.bracket(fa)(
      client => {
        fs2.Stream.eval(F.delay(S3Store[F](client, sse)))
      },
      client => F.delay(client.shutdown())
    )
  }

}
