package com.datastax.driver.spark.util

import java.io.{IOException, FileOutputStream, File}
import java.net.InetAddress

import com.datastax.driver.spark.connector.CassandraConnectorConf
import com.google.common.io.Files

class CassandraServerRunner(val configTemplate: String) {

  val tempDir = IOUtils.mkdir(new File(Files.createTempDir(), "cassandra-driver-spark"))
  val workDir = IOUtils.mkdir(new File(tempDir, "cassandra"))
  val dataDir = IOUtils.mkdir(new File(workDir, "data"))
  val commitLogDir = IOUtils.mkdir(new File(workDir, "commitlog"))
  val cachesDir = IOUtils.mkdir(new File(workDir, "saved_caches"))     
  val confDir = IOUtils.mkdir(new File(tempDir, "conf"))
  val confFile = new File(confDir, "cassandra.yaml")

  private val properties = Map("cassandra_dir" -> workDir.toString)
  IOUtils.closeAfterUse(ClassLoader.getSystemResourceAsStream(configTemplate)) { input =>
    IOUtils.closeAfterUse(new FileOutputStream(confFile)) { output =>
      IOUtils.copyTextFileWithVariableSubstitution(input, output, properties)
    }
  }

  private val classPath = System.getProperty("java.class.path")
  private val javaBin = System.getProperty("java.home") + "/bin/java"
  private val cassandraConfProperty = "-Dcassandra.config=file:" + confFile.toString
  private val superuserSetupDelayProperty = "-Dcassandra.superuser_setup_delay_ms=0"
  private val jammAgent = classPath.split(File.pathSeparator).find(_.matches(".*jamm.*\\.jar"))
  private val jammAgentProperty = jammAgent.map("-javaagent:" + _).getOrElse("")
  private val cassandraMainClass = "org.apache.cassandra.service.CassandraDaemon"

  private val process = new ProcessBuilder()
    .command(javaBin,
      cassandraConfProperty, jammAgentProperty, superuserSetupDelayProperty, "-cp", classPath,
      cassandraMainClass, "-f")
    .inheritIO()
    .start()

  if (!IOUtils.waitForPortOpen(InetAddress.getByName("127.0.0.1"), CassandraConnectorConf.DefaultNativePort, 10000))
    throw new IOException("Failed to start Cassandra.")

  def destroy() {
    process.destroy()
    process.waitFor()
  }
}


