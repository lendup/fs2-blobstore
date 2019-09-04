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

import java.util.Date

final case class Path(root: String, key: String, size: Option[Long], isDir: Boolean, lastModified: Option[Date]) {
  override def toString: String = s"$root/$key"
}

object Path {

  val SEP = '/'

  def apply(path: String): Path = {
    val x = path
      .replaceFirst("^([a-zA-Z][-.+a-zA-Z0-9]+:/)?/", "") // The regex identifies a scheme as described in https://www.ietf.org/rfc/rfc2396.txt chapter 3.1 "Scheme Component"
      .split("/", 2)
    val r = x(0)
    val k = if (x.length <= 1) "" else x(1)
    Path(r, k, None, path.lastOption.contains(SEP), None)
  }
}

trait PathOps {
  import Path.SEP

  implicit class AppendOps(path: Path) {

    /**
      * Concatenate string to end of path separated by Path.SEP
      * @param str String to append
      * @return concatenated Path
      */
    def /(str: String): Path = {
      val separator = if (path.key.lastOption.contains(SEP) || str.headOption.contains(SEP)) "" else SEP
      val k = path.key.trim match {
        case "" => str
        case _ => s"${path.key}$separator$str"
      }
      Path(path.root, k, None, k.lastOption.contains(SEP), None)
    }

    /**
      * Concatenate other Path to end of this path separated by Path.SEP,
      * this method ignores the other path root in favor of this path root.
      * @param other Path to append
      * @return concatenated Path
      */
    def /(other: Path): Path = /(other.key)

    /**
      * Split path.key by Path.SEP, if path.key.isEmpty, try
      * splitting path.root, if it is empty too, return empty string
      * @return String file name
      */
    def filename: String = {
      path.key.split(SEP).lastOption.filterNot(_.isEmpty).map(s => if (path.isDir) s + SEP else s)
        .getOrElse(path.root.split(SEP).lastOption.getOrElse("") + SEP)
    }
  }

}

object PathOps extends PathOps
