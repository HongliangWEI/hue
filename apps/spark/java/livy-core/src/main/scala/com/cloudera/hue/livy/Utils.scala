package com.cloudera.hue.livy

import java.io.{FileInputStream, InputStreamReader, File}
import java.util.Properties
import java.util.concurrent.TimeUnit

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.concurrent.TimeoutException
import scala.concurrent.duration.Duration

object Utils {
  def getPropertiesFromFile(filename: String): Map[String, String] = {
    val file = new File(filename)
    require(file.exists(), s"Properties file $file does not exist")
    require(file.isFile, s"Properties file $file is not a normal file")

    val inReader = new InputStreamReader(new FileInputStream(file), "UTF-8")
    try {
      val properties = new Properties()
      properties.load(inReader)
      properties.stringPropertyNames().map(k => (k, properties(k).trim())).toMap
    } finally {
      inReader.close()
    }
  }

  def getDefaultPropertiesFile(env: Map[String, String] = sys.env): String = {
    env.get("LIVY_CONF_DIR")
      .orElse(env.get("LIVY_HOME").map(path => s"$path${File.separator}conf"))
      .map(path => new File(s"$path${File.separator}livy-defaults.conf"))
      .filter(_.isFile)
      .map(_.getAbsolutePath)
      .orNull
  }

  def loadDefaultLivyProperties(conf: LivyConf, filePath: String = null) = {
    val path = Option(filePath).getOrElse(getDefaultPropertiesFile())
    Option(path).foreach { path =>
      getPropertiesFromFile(path).filter { case (k, v) =>
        k.startsWith("livy.")
      }.foreach { case (k, v) =>
        conf.setIfMissing(k, v)
        sys.props.getOrElseUpdate(k, v)
      }
    }
  }

  def jarOfClass(cls: Class[_]): Option[String] = {
    val uri = cls.getResource("/" + cls.getName.replace('.', '/') + ".class")
    if (uri != null) {
      val uriStr = uri.toString
      if (uriStr.startsWith("jar:file:")) {
        Some(uriStr.substring("jar:file:".length, uriStr.indexOf("!")))
      } else {
        None
      }
    } else {
      None
    }
  }

  /**
   * Checks if event has occurred during some time period. This performs an exponential backoff
   * to limit the poll calls.
   *
   * @param checkForEvent
   * @param atMost How long to wait for. Defaults to 5 minutes.
   * @throws java.util.concurrent.TimeoutException
   * @throws java.lang.InterruptedException
   * @return
   */
  @throws(classOf[TimeoutException])
  @throws(classOf[InterruptedException])
  final def waitUntil(checkForEvent: () => Boolean,
                      atMost: Duration = Duration(5, TimeUnit.MINUTES)) = {
    val endTime = System.currentTimeMillis() + atMost.toMillis

    @tailrec
    def aux(count: Int): Unit = {
      if (!checkForEvent()) {
        val now = System.currentTimeMillis()

        if (now < endTime) {
          val sleepTime = Math.max(10 * (2 << (count - 1)), 1000)
          Thread.sleep(sleepTime)
          aux(count + 1)
        } else {
          throw new TimeoutException
        }
      }
    }

    aux(1)
  }
}
